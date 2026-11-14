package hu.billman.konsolessh.sync

import hu.billman.konsolessh.data.ConnectionSyncState
import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncTriggeringRepositoryTest {

    @Test
    fun `saves and deletes ask for a sync, the sync's own writes do not`() = runBlocking {
        var requests = 0
        val repo = SyncTriggeringRepository(FakeConnectionRepository()) { requests++ }

        repo.saveOne(ConnectionConfig(id = "a"))
        repo.delete("a")
        repo.replaceFromSync(ConnectionSyncState(emptyList(), emptyMap()))
        repo.load()

        assertEquals(2, requests)
    }
}
