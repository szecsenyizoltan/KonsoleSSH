package hu.billman.konsolessh.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CRUD, failure and migration behaviour of the DataStore connection store,
 * with [FakeCryptoBox] so that Robolectric needs no Android Keystore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoBoxConnectionRepositoryTest {

    private lateinit var context: Context
    private lateinit var file: File

    private fun deletedFile() = File(file.parentFile, file.name + ".deleted")
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.filesDir, "datastore/connections_test.pb")
        clearAll()
    }

    @After
    fun tearDown() {
        closeScopes()
        clearAll()
    }

    private fun clearAll() {
        file.delete()
        deletedFile().delete()
        listOf(BOX, LEGACY_ENC, LEGACY_PLAIN).forEach { context.deleteSharedPreferences(it) }
    }

    private fun closeScopes() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    /**
     * One DataStore per file at a time: a new repository closes the previous
     * one first, and waits for it, since DataStore releases the file only when
     * its scope has completed.
     */
    private var now = 1_000L

    private fun newRepo(box: CryptoBox = FakeCryptoBox()): CryptoBoxConnectionRepository {
        closeScopes()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return CryptoBoxConnectionRepository.create(
            context,
            box,
            scope,
            storeFile = file,
            deletedFile = deletedFile(),
            legacyStores = LegacyConnectionsMigration.Stores(box = BOX, encrypted = LEGACY_ENC, plain = LEGACY_PLAIN),
            clock = { now },
        )
    }

    private fun cfg(id: String, name: String = id) =
        ConnectionConfig(id = id, name = name, host = "10.0.0.1", username = "root")

    private fun stored(): ByteArray? = file.takeIf { it.exists() }?.readBytes()

    private suspend fun assertStoreError(block: suspend () -> Unit) {
        try {
            block()
            fail("expected ConnectionStoreException")
        } catch (_: ConnectionStoreException) {
            // expected
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test
    fun `load on clean state returns empty list`() = runBlocking {
        assertTrue(newRepo().load().isEmpty())
    }

    @Test
    fun `saveOne adds new, updates existing and load sorts by displayName`() = runBlocking {
        val repo = newRepo()
        repo.saveOne(cfg("b", "Beta"))
        repo.saveOne(cfg("a", "Alpha"))
        repo.saveOne(cfg("b", "Bravo"))
        assertEquals(listOf("Alpha", "Bravo"), newRepo().load().map { it.name })
    }

    @Test
    fun `get returns the matching connection or null`() = runBlocking {
        val repo = newRepo()
        repo.saveOne(cfg("a", "Alpha"))
        assertEquals("Alpha", repo.get("a")?.name)
        assertNull(repo.get("missing"))
    }

    @Test
    fun `delete removes matching id only`() = runBlocking {
        val repo = newRepo()
        repo.saveOne(cfg("a"))
        repo.saveOne(cfg("b"))
        repo.delete("a")
        assertEquals(listOf("b"), repo.load().map { it.id })
    }

    @Test
    fun `every field survives a round trip, passphrase and jump included`() = runBlocking {
        val full = ConnectionConfig(
            id = "full",
            name = "Full",
            host = "192.168.122.10",
            port = 2222,
            username = "admin",
            authType = ConnectionConfig.AuthType.PRIVATE_KEY,
            password = "pw",
            privateKey = "-----BEGIN KEY-----",
            privateKeyPassphrase = "secret",
            jumpConnectionId = "gw",
            jumpHost = "legacy.example.com",
            jumpPort = 2200,
            jumpUsername = "j",
            jumpPassword = "jp",
        )
        newRepo().saveOne(full)
        assertEquals(full.copy(updatedAt = now), newRepo().get("full"))
    }

    @Test
    fun `the file holds only encrypted bytes`() = runBlocking {
        newRepo().saveOne(cfg("a", "VisibleName").copy(password = "VisiblePassword"))
        val text = String(stored()!!, Charsets.ISO_8859_1)
        assertFalse(text.contains("VisibleName"))
        assertFalse(text.contains("VisiblePassword"))
    }

    // ── Failures: an unreadable store is never written ────────────────────────

    @Test
    fun `a damaged file throws instead of looking empty`() = runBlocking {
        file.parentFile?.mkdirs()
        file.writeBytes("!!!not-a-store".toByteArray())
        assertStoreError { newRepo().load() }
    }

    @Test
    fun `saveOne and delete do not overwrite an unreadable store`() = runBlocking {
        newRepo().saveOne(cfg("a", "Alpha"))
        val original = stored()
        // Same data, but the key "changed": decrypt fails like a Keystore failure would.
        val otherKey = newRepo(FakeCryptoBox(maskKey = 0x11))

        assertStoreError { otherKey.saveOne(cfg("b")) }
        assertStoreError { otherKey.delete("a") }

        assertArrayEquals(original, stored())
        assertEquals(listOf("a"), newRepo().load().map { it.id })
    }

    @Test
    fun `encrypt failure throws and writes nothing, never plaintext`() = runBlocking {
        val failingBox = object : CryptoBox {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("keystore broke")
            override fun decrypt(payload: ByteArray): ByteArray = payload
        }
        assertStoreError { newRepo(failingBox).saveOne(cfg("a", "Alpha")) }
        assertFalse(String(stored() ?: ByteArray(0), Charsets.ISO_8859_1).contains("Alpha"))
    }

    // ── Migration from the stores of older versions ───────────────────────────

    private fun putBoxPayload(value: String) {
        context.getSharedPreferences(BOX, Context.MODE_PRIVATE).edit().putString("payload", value).commit()
    }

    private fun boxPayloadOf(vararg configs: ConnectionConfig, box: CryptoBox = FakeCryptoBox()): String =
        Base64.encodeToString(box.encrypt(Gson().toJson(configs.toList()).toByteArray()), Base64.NO_WRAP)

    private fun putLegacyPlain(vararg configs: ConnectionConfig) {
        context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)
            .edit().putString("connections", Gson().toJson(configs.toList())).commit()
    }

    private fun prefsFile(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    @Test
    fun `the 1_1_x payload is migrated, then its preferences are deleted`() = runBlocking {
        putBoxPayload(boxPayloadOf(cfg("b", "Beta"), cfg("a", "Alpha")))

        assertEquals(listOf("Alpha", "Beta"), newRepo().load().map { it.name })
        assertFalse(context.getSharedPreferences(BOX, Context.MODE_PRIVATE).contains("payload"))
        assertEquals(listOf("Alpha", "Beta"), newRepo().load().map { it.name })
    }

    @Test
    fun `plaintext fallback written by older versions is migrated and stored encrypted`() = runBlocking {
        val json = Gson().toJson(listOf(cfg("old", "OldName")))
        putBoxPayload(Base64.encodeToString("PLAIN\u0000".toByteArray() + json.toByteArray(), Base64.NO_WRAP))

        assertEquals(listOf("old"), newRepo().load().map { it.id })
        assertFalse(String(stored()!!, Charsets.ISO_8859_1).contains("OldName"))
    }

    @Test
    fun `an unreadable 1_1_x payload fails like an unreadable store and is kept`() = runBlocking {
        val payload = boxPayloadOf(cfg("a"), box = FakeCryptoBox(maskKey = 0x11))
        putBoxPayload(payload)

        assertStoreError { newRepo().load() }
        assertStoreError { newRepo().saveOne(cfg("b")) }
        assertEquals(payload, context.getSharedPreferences(BOX, Context.MODE_PRIVATE).getString("payload", null))
    }

    @Test
    fun `legacy plain preferences are migrated, then deleted`() = runBlocking {
        putLegacyPlain(cfg("legacy1", "Legacy Alpha"), cfg("legacy2", "Legacy Beta"))

        assertEquals(setOf("legacy1", "legacy2"), newRepo().load().map { it.id }.toSet())
        assertFalse(context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE).contains("connections"))
    }

    @Test
    fun `migration runs once, deleting everything keeps it empty`() = runBlocking {
        putLegacyPlain(cfg("x", "Y"))
        val repo = newRepo()
        assertEquals(listOf("Y"), repo.load().map { it.name })
        repo.delete("x")
        assertTrue(newRepo().load().isEmpty())
    }

    @Test
    fun `failed migration write keeps the legacy store for a retry`() = runBlocking {
        putLegacyPlain(cfg("x", "Y"))
        val failingBox = object : CryptoBox {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("keystore broke")
            override fun decrypt(payload: ByteArray): ByteArray = payload
        }

        assertStoreError { newRepo(failingBox).load() }
        assertTrue(context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE).contains("connections"))

        assertEquals(listOf("x"), newRepo().load().map { it.id })
    }

    @Test
    fun `a repository with its own store names never touches the default old stores`() = runBlocking {
        val real = context.getSharedPreferences(LegacyConnectionsMigration.PLAIN_LEGACY_PREFS, Context.MODE_PRIVATE)
        real.edit().putString("connections", Gson().toJson(listOf(cfg("real")))).commit()
        try {
            assertTrue(newRepo().load().isEmpty())
            assertTrue(real.contains("connections"))
        } finally {
            context.deleteSharedPreferences(LegacyConnectionsMigration.PLAIN_LEGACY_PREFS)
        }
    }

    @Test
    fun `a fresh install does not open the EncryptedSharedPreferences store`() = runBlocking {
        assertTrue(newRepo().load().isEmpty())
        assertFalse(prefsFile(LEGACY_ENC).exists())
    }

    // ── Sync state ────────────────────────────────────────────────────────────

    @Test
    fun `a save stamps the change time`() = runBlocking {
        val repo = newRepo()
        repo.saveOne(cfg("a"))
        now = 2_000L
        repo.saveOne(cfg("a", "Renamed"))
        assertEquals(2_000L, newRepo().get("a")?.updatedAt)
    }

    @Test
    fun `a delete is recorded with its time and survives a restart`() = runBlocking {
        val repo = newRepo()
        repo.saveOne(cfg("a"))
        now = 5_000L
        repo.delete("a")
        val state = newRepo().syncState()
        assertTrue(state.connections.isEmpty())
        assertEquals(mapOf("a" to 5_000L), state.deleted)
    }

    @Test
    fun `replacing from sync keeps the given times`() = runBlocking {
        val repo = newRepo()
        val merged = ConnectionSyncState(
            connections = listOf(cfg("b").copy(updatedAt = 42L)),
            deleted = mapOf("a" to 7L),
        )
        repo.replaceFromSync(merged)
        assertEquals(merged, newRepo().syncState())
    }

    @Test
    fun `a store written before change times reads them as zero`() = runBlocking {
        val box = FakeCryptoBox()
        file.parentFile?.mkdirs()
        val old = """[{"id":"a","name":"Old","host":"h","port":22,"username":"u"}]"""
        file.writeBytes(box.encrypt(old.toByteArray()))
        assertEquals(0L, newRepo(box).get("a")?.updatedAt)
    }

    private companion object {
        const val BOX = "konsole_connections_box_test"
        const val LEGACY_ENC = "konsole_connections_enc_test"
        const val LEGACY_PLAIN = "konsole_connections_test"
    }
}
