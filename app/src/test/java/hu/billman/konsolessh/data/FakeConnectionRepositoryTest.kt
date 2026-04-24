package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A FakeConnectionRepository önmagában egy teszt-eszköz, de a kontraktus
 * (CRUD + displayName szerinti sorrendezés) tesztelése biztosítja, hogy
 * nem tér el a valós CryptoBoxConnectionRepository viselkedésétől
 * a ViewModel-szintű teszteknél.
 */
class FakeConnectionRepositoryTest {

    private fun cfg(id: String, name: String = "", host: String = "h") =
        ConnectionConfig(id = id, name = name, host = host, username = "u")

    @Test
    fun `load on empty returns empty list`() {
        val repo = FakeConnectionRepository()
        assertTrue(repo.load().isEmpty())
    }

    @Test
    fun `saveOne adds when id is new`() {
        val repo = FakeConnectionRepository()
        repo.saveOne(cfg("a", "Alpha"))
        val list = repo.load()
        assertEquals(1, list.size)
        assertEquals("Alpha", list[0].name)
    }

    @Test
    fun `saveOne updates when id already exists`() {
        val repo = FakeConnectionRepository(listOf(cfg("a", "Alpha")))
        repo.saveOne(cfg("a", "Alpha2"))
        val list = repo.load()
        assertEquals(1, list.size)
        assertEquals("Alpha2", list[0].name)
    }

    @Test
    fun `delete removes matching id only`() {
        val repo = FakeConnectionRepository(
            listOf(cfg("a", "Alpha"), cfg("b", "Beta")),
        )
        repo.delete("a")
        val list = repo.load()
        assertEquals(1, list.size)
        assertEquals("b", list[0].id)
    }

    @Test
    fun `delete with unknown id is no-op`() {
        val repo = FakeConnectionRepository(listOf(cfg("a", "Alpha")))
        repo.delete("nonexistent")
        assertEquals(1, repo.load().size)
    }

    @Test
    fun `save replaces full list`() {
        val repo = FakeConnectionRepository(listOf(cfg("a", "Alpha")))
        repo.save(listOf(cfg("b", "Beta"), cfg("c", "Cappa")))
        val list = repo.load()
        assertEquals(2, list.size)
        assertNull(list.firstOrNull { it.id == "a" })
    }

    @Test
    fun `load returns entries sorted by displayName case-insensitive`() {
        val repo = FakeConnectionRepository(
            listOf(
                cfg("1", name = "zeta"),
                cfg("2", name = "Alpha"),
                cfg("3", name = "beta"),
            ),
        )
        val names = repo.load().map { it.name }
        assertEquals(listOf("Alpha", "beta", "zeta"), names)
    }
}
