package hu.billman.konsolessh.ui

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tesztek a ConnectionsViewModel-hez FakeConnectionRepository-val.
 * Mivel a VM a repository-hívásokat szinkronban futtatja a Main-szálon,
 * nincs szükség runTest vagy TestDispatcher szerkezetre.
 */
class ConnectionsViewModelTest {

    private fun cfg(id: String, name: String = id) =
        ConnectionConfig(id = id, name = name, host = "10.0.0.1", username = "root")

    @Test
    fun `initial state is empty list`() {
        val vm = ConnectionsViewModel(FakeConnectionRepository())
        assertTrue(vm.connections.value.isEmpty())
    }

    @Test
    fun `init loads existing entries from repository`() {
        val repo = FakeConnectionRepository(listOf(cfg("a", "Alpha"), cfg("b", "Beta")))
        val vm = ConnectionsViewModel(repo)
        assertEquals(2, vm.connections.value.size)
    }

    @Test
    fun `saveOne updates StateFlow`() {
        val vm = ConnectionsViewModel(FakeConnectionRepository())
        vm.saveOne(cfg("a", "Alpha"))
        assertEquals(1, vm.connections.value.size)
        assertEquals("Alpha", vm.connections.value[0].name)
    }

    @Test
    fun `delete removes matching id and updates StateFlow`() {
        val repo = FakeConnectionRepository(listOf(cfg("a", "Alpha"), cfg("b", "Beta")))
        val vm = ConnectionsViewModel(repo)
        vm.delete("a")
        assertEquals(1, vm.connections.value.size)
        assertEquals("b", vm.connections.value[0].id)
    }

    @Test
    fun `refresh re-reads from repository`() {
        val repo = FakeConnectionRepository()
        val vm = ConnectionsViewModel(repo)
        // Repository-be közvetlen írás (a VM megkerülésével): valós appban
        // ez történik, amikor a NewConnectionDialog a legacy wrapper-en át ment.
        repo.saveOne(cfg("x", "X"))
        vm.refresh()
        assertEquals(1, vm.connections.value.size)
        assertEquals("X", vm.connections.value[0].name)
    }
}
