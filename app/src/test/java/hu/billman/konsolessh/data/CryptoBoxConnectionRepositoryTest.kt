package hu.billman.konsolessh.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
            legacyStores = LegacyStoresCleanup.Stores(box = BOX, encrypted = LEGACY_ENC, plain = LEGACY_PLAIN),
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
            auth = ConnectionConfig.Auth.PrivateKey("-----BEGIN KEY-----", passphrase = "secret"),
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
        newRepo().saveOne(cfg("a", "VisibleName").copy(auth = ConnectionConfig.Auth.Password("VisiblePassword")))
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

    // ── The stores of 1.x ─────────────────────────────────────────────────────

    private fun putOldStore(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("connections", "[]").commit()
    }

    private fun prefsFile(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    @Test
    fun `the stores of 1_x are deleted unread, and the store starts empty`() = runBlocking {
        listOf(BOX, LEGACY_ENC, LEGACY_PLAIN).forEach(::putOldStore)

        assertTrue(newRepo().load().isEmpty())
        listOf(BOX, LEGACY_ENC, LEGACY_PLAIN).forEach { assertFalse(it, prefsFile(it).exists()) }
    }

    @Test
    fun `saved connections are kept when an old store is cleaned up`() = runBlocking {
        newRepo().saveOne(cfg("a", "Alpha"))
        putOldStore(LEGACY_PLAIN)

        assertEquals(listOf("Alpha"), newRepo().load().map { it.name })
        assertFalse(prefsFile(LEGACY_PLAIN).exists())
    }

    @Test
    fun `a repository with its own store names never touches the default old stores`() = runBlocking {
        val default = LegacyStoresCleanup.Stores().plain
        putOldStore(default)
        try {
            assertTrue(newRepo().load().isEmpty())
            assertTrue(prefsFile(default).exists())
        } finally {
            context.deleteSharedPreferences(default)
        }
    }

    @Test
    fun `a fresh install creates none of the old stores`() = runBlocking {
        assertTrue(newRepo().load().isEmpty())
        listOf(BOX, LEGACY_ENC, LEGACY_PLAIN).forEach { assertFalse(it, prefsFile(it).exists()) }
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
