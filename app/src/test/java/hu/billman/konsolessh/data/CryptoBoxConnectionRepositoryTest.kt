package hu.billman.konsolessh.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A CryptoBoxConnectionRepository CRUD- és migrációs ellenőrzése.
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
        context.deleteSharedPreferences("konsole_connections_box")
        context.deleteSharedPreferences("konsole_connections_enc")
        context.deleteSharedPreferences("konsole_connections")
    }

    private fun newRepo() = CryptoBoxConnectionRepository(context, FakeCryptoBox())

    private fun cfg(id: String, name: String = id) =
        ConnectionConfig(id = id, name = name, host = "10.0.0.1", username = "root")

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test
    fun `load on clean state returns empty list`() {
        val repo = newRepo()
        assertTrue(repo.load().isEmpty())
    }

    @Test
    fun `save then load round-trips and sorts by displayName`() {
        val repo = newRepo()
        repo.save(listOf(cfg("2", "Zeta"), cfg("1", "Alpha")))
        val list = repo.load()
        assertEquals(2, list.size)
        assertEquals("Alpha", list[0].name)
        assertEquals("Zeta", list[1].name)
    }

    @Test
    fun `saveOne adds when id new and updates when id exists`() {
        val repo = newRepo()
        repo.saveOne(cfg("a", "Alpha"))
        repo.saveOne(cfg("b", "Beta"))
        repo.saveOne(cfg("a", "Alpha2"))
        val list = repo.load()
        assertEquals(2, list.size)
        assertEquals("Alpha2", list.first { it.id == "a" }.name)
    }

    @Test
    fun `delete removes matching id only`() {
        val repo = newRepo()
        repo.save(listOf(cfg("a"), cfg("b"), cfg("c")))
        repo.delete("b")
        val ids = repo.load().map { it.id }.toSet()
        assertEquals(setOf("a", "c"), ids)
    }

    @Test
    fun `load returns empty when encrypted payload is corrupted`() {
        val repo = newRepo()
        context.getSharedPreferences("konsole_connections_box", Context.MODE_PRIVATE)
            .edit()
            .putString("payload", "!!!not-valid-base64-garbage")
            .apply()
        assertTrue(repo.load().isEmpty())
    }

    // ── Migráció a legacy EncryptedSharedPreferences-ből ──────────────────────

    @Test
    fun `load migrates from legacy plain-prefs when present`() {
        // Régi plain-prefs formátumot szimulálunk (security-crypto alpha egyik
        // fallback-ága is ezt használta): a "konsole_connections" nevű SP-ban
        // egy "connections" kulcsnál JSON-formátumban a lista.
        val legacyJson = Gson().toJson(
            listOf(cfg("legacy1", "Legacy Alpha"), cfg("legacy2", "Legacy Beta")),
        )
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit()
            .putString("connections", legacyJson)
            .apply()

        val repo = newRepo()
        val list = repo.load()
        assertEquals(2, list.size)
        val ids = list.map { it.id }.toSet()
        assertTrue(ids.contains("legacy1"))
        assertTrue(ids.contains("legacy2"))
    }

    @Test
    fun `legacy plain-prefs is deleted after successful migration`() {
        val legacyJson = Gson().toJson(listOf(cfg("x", "Y")))
        val legacyPrefs = context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("connections", legacyJson).apply()

        newRepo().load()

        val refreshed = context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
        assertFalse(
            "legacy plain prefs should no longer contain the connections key",
            refreshed.contains("connections"),
        )
    }

    @Test
    fun `migration is idempotent — runs once`() {
        val legacyJson = Gson().toJson(listOf(cfg("x", "Y")))
        context.getSharedPreferences("konsole_connections", Context.MODE_PRIVATE)
            .edit()
            .putString("connections", legacyJson)
            .apply()

        val repo1 = newRepo()
        val firstLoad = repo1.load()
        assertEquals(1, firstLoad.size)

        // Egy új repo példánnyal a második load már csak az új formátumból
        // olvas — a legacy lekérdezés nem fut újra, és nincs duplikáció.
        val repo2 = newRepo()
        val secondLoad = repo2.load()
        assertEquals(1, secondLoad.size)
        assertEquals("Y", secondLoad[0].name)
    }

    @Test
    fun `no legacy data → empty load sets migration flag to prevent retry`() {
        val repo = newRepo()
        assertTrue(repo.load().isEmpty())
        // Flag lerakva → egy új sor írás után is megmaradunk az új prefs-ben
        repo.saveOne(cfg("new", "NewOne"))
        val reloaded = newRepo().load()
        assertEquals(1, reloaded.size)
        assertEquals("NewOne", reloaded[0].name)
    }
}
