package hu.billman.konsolessh.sync

import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionSyncState
import hu.billman.konsolessh.ssh.HostKeyStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.spec.SecretKeySpec

/**
 * Keeps the saved connections and host keys in step with the encrypted
 * file in the user's Drive. A sync reads the file, merges it with this
 * device's state ([SyncMerge]), stores the result here and writes it
 * back. Saves and deletes ask for a sync through [requestSync], which
 * waits a moment so a burst of changes becomes one upload. Until that
 * upload succeeds, [scheduler] keeps a later one pending, so a change is
 * not left behind when the process stops or the network is down.
 */
class SyncManager(
    private val connections: ConnectionRepository,
    private val hostKeys: HostKeyStore,
    private val keys: SyncKeyStorage,
    private val remote: SyncFileStore,
    private val scope: CoroutineScope,
    private val scheduler: SyncScheduler = SyncScheduler.None,
    private val timing: Timing = Timing(),
) {

    /**
     * The clock, the key derivation's cost and the wait before an upload.
     * Tests make them fast and predictable; the defaults are the real ones.
     */
    class Timing(
        val clock: () -> Long = System::currentTimeMillis,
        val iterations: Int = SyncCipher.ITERATIONS,
        val delayMs: Long = REQUEST_DELAY_MS,
        val cpu: CoroutineDispatcher = Dispatchers.Default,
    )

    sealed interface Status {
        /** Sync is not set up on this device. */
        data object Off : Status

        data class Ready(val lastSyncAt: Long?) : Status

        data object Syncing : Status

        /** The last sync failed; [cause] tells why, e.g. a [DriveAuthorizer.ConsentNeededException]. */
        data class Failed(val cause: Throwable) : Status
    }

    enum class Enabled { JOINED, CREATED }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()
    private var pending: Job? = null

    /** Reads whether sync is set up here; call once at start. */
    suspend fun start() {
        _status.value = keys.load()?.let { Status.Ready(it.lastSyncAt) } ?: Status.Off
    }

    /** Whether the user's Drive already holds a sync file, so the passphrase joins it. */
    suspend fun backupExists(): Boolean = remote.read() != null

    /**
     * Turns sync on: joins the existing file if the passphrase opens it,
     * or starts a new one. Throws [SyncCipher.WrongPassphraseException] if
     * a file exists that the passphrase does not open.
     */
    suspend fun enable(passphrase: CharArray): Enabled {
        val existing = remote.read()
        val params = existing?.let { SyncCipher.keyParamsOf(it) } ?: SyncCipher.newKeyParams(timing.iterations)
        val secret = withContext(timing.cpu) { SyncCipher.deriveKey(passphrase, params) }
        if (existing != null) SyncCipher.open(existing, secret)
        keys.save(SyncKey(secret.encoded, params))
        syncNow()
        return if (existing != null) Enabled.JOINED else Enabled.CREATED
    }

    /** Turns sync off here. The local data and the file in Drive stay. */
    suspend fun disable() {
        pending?.cancel()
        scheduler.cancel()
        mutex.withLock { keys.save(null) }
        _status.value = Status.Off
    }

    /**
     * Asks for a sync after a short wait, so a burst of changes becomes one
     * upload; [now] skips the wait. [now] is also used without a local change
     * (the app came to the foreground), so only a waiting request keeps a
     * later upload pending.
     */
    fun requestSync(now: Boolean = false) {
        if (_status.value == Status.Off) return
        if (!now) scheduler.schedule()
        pending?.cancel()
        pending = scope.launch {
            if (!now) delay(timing.delayMs)
            runCatching { syncNow() }.onSuccess { scheduler.cancel() }
        }
    }

    /** Runs a sync that is still waiting at once, e.g. as the app goes to the background. */
    fun flush() {
        if (pending?.isActive == true) requestSync(now = true)
    }

    /** Syncs at once. Failures are reported in [status] and rethrown. */
    suspend fun syncNow() = mutex.withLock {
        val key = keys.load() ?: return@withLock
        _status.value = Status.Syncing
        try {
            sync(key)
            val done = key.copy(lastSyncAt = timing.clock())
            keys.save(done)
            _status.value = Status.Ready(done.lastSyncAt)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            _status.value = Status.Failed(e)
            throw e
        }
    }

    private suspend fun sync(key: SyncKey) {
        val secret = SecretKeySpec(key.secret, "AES")
        val remoteText = remote.read()
        val remotePayload = remoteText?.let { SyncPayload.decode(SyncCipher.open(it, secret)) }
        val local = localPayload()
        var merged = remotePayload?.let { SyncMerge.merge(local, it) } ?: local
        // The user may have saved something meanwhile: merge that in too, so it is not overwritten.
        merged = SyncMerge.merge(merged, localPayload())
        connections.replaceFromSync(ConnectionSyncState(merged.connections, merged.deletedConnections))
        hostKeys.replaceFromSync(merged.hostKeys.map { HostKeyStore.Change(it.host, it.type, it.key, it.at) })
        if (remotePayload == null || !sameContent(merged, remotePayload)) {
            remote.write(SyncCipher.seal(merged.encode(), secret, key.params))
        }
    }

    private suspend fun localPayload(): SyncPayload {
        val state = connections.syncState()
        val keysNow = hostKeys.changes().map { SyncedHostKey(it.host, it.type, it.key, it.at) }
        return SyncPayload(state.connections, state.deleted, keysNow)
    }

    private fun sameContent(a: SyncPayload, b: SyncPayload) =
        a.connections.toSet() == b.connections.toSet() &&
            a.deletedConnections == b.deletedConnections &&
            a.hostKeys.toSet() == b.hostKeys.toSet()

    private companion object {
        const val REQUEST_DELAY_MS = 3_000L
    }
}
