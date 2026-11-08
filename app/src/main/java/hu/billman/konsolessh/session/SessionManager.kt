package hu.billman.konsolessh.session

import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ssh.JumpChain
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ssh.SshErrorClassifier
import hu.billman.konsolessh.ssh.TerminalSession
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Az SSH-fülek egyetlen igazságforrása, alkalmazás-élettartamú. A felület a
 * [state]-et figyeli és parancsokat ad; a foreground service csak életben
 * tartja a folyamatot és az értesítést frissíti.
 *
 * A fül a mentett kapcsolat azonosítóját tárolja, nem a titkokat: a konfig a
 * kapcsolódáskor töltődik be a repository-ból.
 */
class SessionManager(
    private val repository: ConnectionRepository,
    private val sessionFactory: TerminalSessionFactory,
    private val messages: TerminalMessages,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow(SessionsState())
    val state: StateFlow<SessionsState> = _state.asStateFlow()

    private class Runtime(val broker: PromptBroker) {
        val buffer = OutputBuffer()
        val output = MutableSharedFlow<OutputChunk>(
            extraBufferCapacity = OUTPUT_FLOW_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        @Volatile var session: TerminalSession? = null
        var job: Job? = null
        var promptJob: Job? = null
    }

    private val runtimes = ConcurrentHashMap<String, Runtime>()

    /** Új fület nyit egy mentett kapcsolathoz; a kapcsolódást a [connect] indítja. */
    fun openTab(connection: ConnectionConfig): String {
        val id = UUID.randomUUID().toString()
        val runtime = Runtime(PromptBroker())
        runtimes[id] = runtime
        runtime.promptJob = runtime.broker.pending
            .onEach { prompt -> updateTab(id) { it.copy(prompt = prompt) } }
            .launchIn(scope)
        val tab = TabState(
            id = id,
            connectionId = connection.id,
            title = connection.displayName(),
            displayName = connection.displayName(),
            host = connection.host,
        )
        _state.update { it.copy(tabs = it.tabs + tab) }
        return id
    }

    /** Kapcsolódik, ha a fül nem kapcsolódik épp és nincs kapcsolatban. */
    fun connect(tabId: String, cols: Int, rows: Int, isReconnect: Boolean = false) {
        val runtime = runtimes[tabId]
        val tab = _state.value.tab(tabId)
        val busy = tab?.status == ConnectionStatus.CONNECTING || tab?.status == ConnectionStatus.CONNECTED
        if (runtime == null || tab == null || busy) return
        runtime.job?.cancel()
        runtime.session?.let { old -> scope.launch(ioDispatcher) { old.disconnect() } }
        updateTab(tabId) { it.copy(status = ConnectionStatus.CONNECTING, error = null) }
        runtime.job = scope.launch {
            val error = try {
                runSession(tabId, tab.connectionId, runtime, cols, rows, isReconnect)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectionStoreException) {
                SshError.StoreUnreadable
            } catch (e: MissingConnectionException) {
                SshError.ConnectionMissing
            } catch (e: Exception) {
                SshErrorClassifier.classify(e)
            }
            updateTab(tabId) { it.copy(status = ConnectionStatus.DISCONNECTED, error = error) }
        }
    }

    private suspend fun runSession(
        tabId: String,
        connectionId: String,
        runtime: Runtime,
        cols: Int,
        rows: Int,
        isReconnect: Boolean,
    ) {
        val config = repository.get(connectionId) ?: throw MissingConnectionException()
        val jumps = JumpChain.resolve(config, repository.load())
        if (!isReconnect && jumps.isEmpty()) emit(runtime, messages.connecting(config.host, config.port))
        val session = sessionFactory.create(config, jumps, runtime.broker)
        runtime.session = session
        session.connect(cols, rows) { emit(runtime, it) }
        updateTab(tabId) { it.copy(status = ConnectionStatus.CONNECTED) }
        withContext(ioDispatcher) {
            val buf = ByteArray(READ_BUFFER_SIZE)
            while (isActive) {
                val n = try {
                    session.read(buf)
                } catch (_: java.io.IOException) {
                    -1
                }
                if (n < 0) break
                if (n > 0) emit(runtime, buf.copyOf(n))
            }
            // The remote side closed: release the jump tunnels too.
            session.disconnect()
        }
    }

    fun closeTab(tabId: String) {
        val runtime = runtimes.remove(tabId) ?: return
        runtime.broker.cancel()
        runtime.job?.cancel()
        runtime.promptJob?.cancel()
        runtime.session?.let { session -> scope.launch(ioDispatcher) { session.disconnect() } }
        _state.update { s -> s.copy(tabs = s.tabs.filterNot { it.id == tabId }) }
    }

    fun closeAll() {
        _state.value.tabs.forEach { closeTab(it.id) }
    }

    fun renameTab(tabId: String, title: String) = updateTab(tabId) { it.copy(title = title) }

    /** A felület megjelenítette a hibát. */
    fun clearError(tabId: String) = updateTab(tabId) { it.copy(error = null) }

    fun answerPrompt(tabId: String, promptId: Long, answer: PromptAnswer) {
        runtimes[tabId]?.broker?.answer(promptId, answer)
    }

    fun output(tabId: String): SharedFlow<OutputChunk>? = runtimes[tabId]?.output?.asSharedFlow()

    fun snapshot(tabId: String): OutputChunk? = runtimes[tabId]?.buffer?.snapshot()

    fun send(tabId: String, bytes: ByteArray) {
        runtimes[tabId]?.session?.send(bytes)
    }

    fun resize(tabId: String, cols: Int, rows: Int) {
        runtimes[tabId]?.session?.resize(cols, rows)
    }

    suspend fun upload(
        tabId: String,
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ) = connectedSession(tabId).upload(input, remoteName, size, onProgress)

    suspend fun deleteRemote(tabId: String, path: String) = connectedSession(tabId).deleteRemote(path)

    private fun connectedSession(tabId: String): TerminalSession =
        runtimes[tabId]?.session?.takeIf { it.isConnected }
            ?: error("tab $tabId is not connected")

    // Append and emit under one lock, so chunk positions reach collectors in order.
    private fun emit(runtime: Runtime, text: String) = emit(runtime, text.toByteArray(Charsets.UTF_8))

    private fun emit(runtime: Runtime, bytes: ByteArray) {
        synchronized(runtime.buffer) { runtime.output.tryEmit(runtime.buffer.append(bytes)) }
    }

    private fun updateTab(tabId: String, transform: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.id == tabId) transform(it) else it }) }
    }

    private class MissingConnectionException : IllegalStateException("connection no longer exists")

    private companion object {
        const val READ_BUFFER_SIZE = 8192
        const val OUTPUT_FLOW_CAPACITY = 256
    }
}
