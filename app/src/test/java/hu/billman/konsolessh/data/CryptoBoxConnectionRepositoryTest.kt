package hu.billman.konsolessh.data

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/**
 * A CryptoBoxConnectionRepository CRUD-, hiba- és migrációs viselkedése.
 * FakeCryptoBox-szal, hogy a Robolectric ne igényeljen Android Keystore-t.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoBoxConnectionRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearAllPrefs()
    }

    @After
    fun tearDown() {
        clearAllPrefs()
    }

    private fun clearAllPrefs() {
        context.deleteSharedPreferences(BOX)
        context.deleteSharedPreferences("konsole_connections_enc")
        context.deleteSharedPreferences(LEGACY_PLAIN)
    }

    private fun newRepo(box: CryptoBox = FakeCryptoBox()) = CryptoBoxConnectionRepository(context, box)

    private fun cfg(id: String, name: String = id) =
        ConnectionConfig(id = id, name = name, host = "10.0.0.1", username = "root")

    private fun payload(): String? =
        context.getSharedPreferences(BOX, Context.MODE_PRIVATE).getString("payload", null)

    private fun putPayload(value: String) {
        context.getSharedPreferences(BOX, Context.MODE_PRIVATE).edit().putString("payload", value).commit()
    }

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
        assertEquals(full, newRepo().get("full"))
    }

    // ── Hibák: olvashatatlan tárra nem írunk ──────────────────────────────────

    @Test
    fun `corrupted payload throws instead of looking empty`() = runBlocking {
        putPayload("!!!not-valid-base64-garbage")
        assertStoreError { newRepo().load() }
    }

    @Test
    fun `saveOne and delete do not overwrite an unreadable store`() = runBlocking {
        val repo = newRepo()
        repo.saveOne(cfg("a", "Alpha"))
        val original = payload()
        // Same data, but the key "changed": decrypt fails like a Keystore failure would.
        val otherKey = newRepo(FakeCryptoBox(maskKey = 0x11))

        assertStoreError { otherKey.saveOne(cfg("b")) }
        assertStoreError { otherKey.delete("a") }

        assertEquals(original, payload())
        assertEquals(listOf("a"), repo.load().map { it.id })
    }

    @Test
    fun `encrypt failure throws and writes nothing, never plaintext`() = runBlocking {
        val failingBox = object : CryptoBox {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("keystore broke")
            override fun decrypt(payload: ByteArray): ByteArray = payload
        }
        assertStoreError { newRepo(failingBox).saveOne(cfg("a", "Alpha")) }
        assertNull(payload())
    }

    @Test
    fun `plaintext fallback written by older versions stays readable and gets encrypted on next write`() =
        runBlocking {
            val json = Gson().toJson(listOf(cfg("old", "Old")))
            putPayload(Base64.encodeToString("PLAIN\u0000".toByteArray() + json.toByteArray(), Base64.NO_WRAP))

            val repo = newRepo()
            assertEquals(listOf("old"), repo.load().map { it.id })

            repo.saveOne(cfg("new", "New"))
            val raw = Base64.decode(payload(), Base64.NO_WRAP)
            assertFalse(String(raw, Charsets.ISO_8859_1).startsWith("PLAIN"))
            assertEquals(listOf("new", "old"), newRepo().load().map { it.id })
        }

    // ── Migráció a régi tárakból ──────────────────────────────────────────────

    private fun putLegacy(vararg configs: ConnectionConfig) {
        context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)
            .edit().putString("connections", Gson().toJson(configs.toList())).commit()
    }

    @Test
    fun `legacy plain-prefs are migrated, then deleted once the new payload exists`() = runBlocking {
        putLegacy(cfg("legacy1", "Legacy Alpha"), cfg("legacy2", "Legacy Beta"))

        assertEquals(setOf("legacy1", "legacy2"), newRepo().load().map { it.id }.toSet())

        val box = context.getSharedPreferences(BOX, Context.MODE_PRIVATE)
        assertTrue(box.contains("payload"))
        assertTrue(box.getBoolean("_migrated_from_legacy_v1", false))
        val legacy = context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)
        assertFalse(legacy.contains("connections"))
    }

    @Test
    fun `migration runs once`() = runBlocking {
        putLegacy(cfg("x", "Y"))
        assertEquals(1, newRepo().load().size)
        assertEquals(listOf("Y"), newRepo().load().map { it.name })
    }

    @Test
    fun `failed migration write keeps the legacy store for a retry`() = runBlocking {
        putLegacy(cfg("x", "Y"))
        val failingBox = object : CryptoBox {
            override fun encrypt(plaintext: ByteArray): ByteArray = error("keystore broke")
            override fun decrypt(payload: ByteArray): ByteArray = payload
        }

        assertEquals(listOf("x"), newRepo(failingBox).load().map { it.id })

        val legacy = context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)
        assertTrue(legacy.contains("connections"))
        val box = context.getSharedPreferences(BOX, Context.MODE_PRIVATE)
        assertFalse(box.getBoolean("_migrated_from_legacy_v1", false))
    }

    private companion object {
        const val BOX = "konsole_connections_box"
        const val LEGACY_PLAIN = "konsole_connections"
    }
}
