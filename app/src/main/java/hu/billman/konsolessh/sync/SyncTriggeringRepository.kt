package hu.billman.konsolessh.sync

import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig

/**
 * The connection store the app uses: every save and delete also asks for
 * a sync. The sync itself writes through the inner store, so its own
 * writes do not trigger another round.
 */
class SyncTriggeringRepository(
    private val inner: ConnectionRepository,
    private val onChange: () -> Unit,
) : ConnectionRepository by inner {

    override suspend fun saveOne(config: ConnectionConfig) {
        inner.saveOne(config)
        onChange()
    }

    override suspend fun delete(id: String) {
        inner.delete(id)
        onChange()
    }
}
