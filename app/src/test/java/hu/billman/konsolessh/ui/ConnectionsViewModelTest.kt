package hu.billman.konsolessh.ui

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    private fun cfg(id: String, name: String = id) =
        ConnectionConfig(id = id, name = name, host = "10.0.0.1", username = "root")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `init loads existing entries sorted`() {
        val vm = ConnectionsViewModel(FakeConnectionRepository(listOf(cfg("b", "Beta"), cfg("a", "Alpha"))))
        assertEquals(listOf("Alpha", "Beta"), vm.state.value.connections.map { it.name })
        assertFalse(vm.state.value.storeError)
    }

    @Test
    fun `delete removes the entry`() {
        val vm = ConnectionsViewModel(FakeConnectionRepository(listOf(cfg("a"), cfg("b"))))
        vm.delete("a")
        assertEquals(listOf("b"), vm.state.value.connections.map { it.id })
    }

    @Test
    fun `refresh picks up changes made elsewhere`() {
        val repo = FakeConnectionRepository()
        val vm = ConnectionsViewModel(repo)
        kotlinx.coroutines.runBlocking { repo.saveOne(cfg("x", "X")) }
        vm.refresh()
        assertEquals(listOf("X"), vm.state.value.connections.map { it.name })
    }

    @Test
    fun `unreadable store sets the error flag and keeps the last list`() {
        val repo = FakeConnectionRepository(listOf(cfg("a")))
        val vm = ConnectionsViewModel(repo)
        repo.unreadable = true
        vm.delete("a")
        assertTrue(vm.state.value.storeError)
        assertEquals(listOf("a"), vm.state.value.connections.map { it.id })
    }
}
