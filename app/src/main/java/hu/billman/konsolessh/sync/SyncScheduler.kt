package hu.billman.konsolessh.sync

/**
 * A sync that runs later even if the app's process is gone by then. The
 * [SyncManager] asks for one when a local change waits to be uploaded and
 * withdraws it once the upload succeeded in the running process.
 */
interface SyncScheduler {
    fun schedule()
    fun cancel()

    /** For tests and for a container without sync. */
    object None : SyncScheduler {
        override fun schedule() = Unit
        override fun cancel() = Unit
    }
}
