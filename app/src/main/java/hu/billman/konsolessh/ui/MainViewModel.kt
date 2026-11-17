package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.OutputChunk
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.session.SessionManager
import hu.billman.konsolessh.session.SessionsState
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ssh.SshErrorClassifier
import hu.billman.konsolessh.terminal.KeyModifiers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The main screen's ViewModel. The screen draws one [UiState]: the tabs'
 * state, which comes from the [SessionManager], its single source, together
 * with what belongs to the screen, such as the questions and the notices
 * shown once. Only the sticky [modifiers] are kept apart, because the key bar
 * changes them on every key press.
 */
class MainViewModel(
    private val sessions: SessionManager,
    private val repository: ConnectionRepository,
    private val settings: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val modifiers = KeyModifiers()

    /** A question the user must answer before something is closed. */
    sealed interface Confirm {
        data class CloseTab(val tabId: String, val title: String) : Confirm
        data class CloseApp(val connected: Int) : Confirm
    }

    /** Short one-time notices. */
    sealed interface Message {
        data class Error(val error: SshError) : Message
        data class UploadUndone(val name: String) : Message
        data class UndoFailed(val error: SshError) : Message
    }

    /**
     * A file upload to the home directory of a tab's server. It runs in the
     * ViewModel, so a rotation does not cancel it.
     */
    sealed interface Upload {
        data class Running(val name: String, val done: Long, val total: Long) : Upload
        data class Done(val tabId: String, val name: String) : Upload
        data class Failed(val error: SshError) : Upload
        data object NotConnected : Upload
    }

    /** A host key or password question, and the tab it belongs to. */
    data class TabPrompt(val tabId: String, val prompt: PendingPrompt)

    /** A failed connect, shown once; the tab closes after it unless the user acts. */
    data class ConnectError(val tabId: String, val error: SshError)

    /**
     * Everything the main screen shows. The nullable fields are one-time
     * events held as state: the screen shows them and then reports them
     * handled, so one that arrives while the screen is away is not lost.
     */
    data class UiState(
        val sessions: SessionsState = SessionsState(),
        /** A freshly opened tab the screen should switch to. */
        val selectTab: String? = null,
        val confirm: Confirm? = null,
        val message: Message? = null,
        val upload: Upload? = null,
        /** All sessions are closed; the screen should finish the app. */
        val exit: Boolean = false,
        /** Group paths open in the tab picker, kept so reopening the picker keeps them. */
        val pickerExpanded: Set<String> = emptySet(),
        /** Saved terminal font size in sp; 0 until loaded, or when none was saved. */
        val fontSizeSp: Float = 0f,
    ) {
        /** The question to show: one at a time, the first tab's that has one. */
        val activePrompt: TabPrompt?
            get() = sessions.tabs.firstNotNullOfOrNull { tab -> tab.prompt?.let { TabPrompt(tab.id, it) } }

        val connectErrors: List<ConnectError>
            get() = sessions.tabs.mapNotNull { tab -> tab.error?.let { ConnectError(tab.id, it) } }

        /**
         * Whether a session needs the foreground service. The screen starts it when
         * this turns true; the service stops itself when no session is left.
         */
        val serviceNeeded: Boolean get() = sessions.activeCount > 0
    }

    private val _state = MutableStateFlow(UiState(sessions = sessions.state.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { sessions.state.collect { tabs -> _state.update { it.copy(sessions = tabs) } } }
        viewModelScope.launch { settings.fontSizeSp.collect { sp -> _state.update { it.copy(fontSizeSp = sp) } } }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    fun onConfirmShown() = _state.update { it.copy(confirm = null) }

    private fun show(message: Message) = _state.update { it.copy(message = message) }

    /** Closing a connected tab needs a confirmation; any other tab closes at once. */
    fun requestCloseTab(tabId: String) {
        val tab = sessions.state.value.tab(tabId) ?: return
        if (tab.status == ConnectionStatus.CONNECTED) {
            _state.update { it.copy(confirm = Confirm.CloseTab(tabId, tab.title)) }
        } else {
            closeTab(tabId)
        }
    }

    /** Exiting with live connections needs a confirmation. */
    fun requestCloseApp() {
        val connected = sessions.state.value.connectedCount
        if (connected == 0) exitApp() else _state.update { it.copy(confirm = Confirm.CloseApp(connected)) }
    }

    fun confirmed(confirm: Confirm) = when (confirm) {
        is Confirm.CloseTab -> closeTab(confirm.tabId)
        is Confirm.CloseApp -> exitApp()
    }

    private fun exitApp() {
        sessions.closeAll()
        _state.update { it.copy(exit = true) }
    }

    fun togglePickerGroup(path: String) = _state.update {
        val open = it.pickerExpanded
        it.copy(pickerExpanded = if (path in open) open - path else open + path)
    }

    fun saveFontSize(sp: Float) {
        viewModelScope.launch { settings.setFontSizeSp(sp) }
    }

    fun openTab(connectionId: String) {
        viewModelScope.launch {
            val config = try {
                repository.get(connectionId)
            } catch (e: ConnectionStoreException) {
                show(Message.Error(SshError.StoreUnreadable))
                return@launch
            }
            if (config == null) {
                show(Message.Error(SshError.ConnectionMissing))
            } else {
                val tabId = sessions.openTab(config)
                _state.update { it.copy(selectTab = tabId) }
            }
        }
    }

    fun onTabSelected() = _state.update { it.copy(selectTab = null) }

    /** Connects only a new tab; a dropped connection waits for the user to reconnect. */
    fun connectIfNew(tabId: String, cols: Int, rows: Int) {
        if (sessions.state.value.tab(tabId)?.status == ConnectionStatus.NONE) sessions.connect(tabId, cols, rows)
    }

    fun reconnect(tabId: String, cols: Int, rows: Int) = sessions.connect(tabId, cols, rows, isReconnect = true)

    fun closeTab(tabId: String) = sessions.closeTab(tabId)

    /** A blank name keeps the current one. */
    fun renameTab(tabId: String, title: String) {
        title.trim().takeIf { it.isNotEmpty() }?.let { sessions.renameTab(tabId, it) }
    }

    fun onErrorShown(tabId: String) = sessions.clearError(tabId)

    fun answerPrompt(tabId: String, promptId: Long, answer: PromptAnswer) =
        sessions.answerPrompt(tabId, promptId, answer)

    /** User input: the sticky modifiers apply here, then reset. */
    fun send(tabId: String, bytes: ByteArray) {
        if (bytes.isNotEmpty()) sessions.send(tabId, modifiers.consume(bytes))
    }

    /** A control sequence that leaves the modifiers alone (from the Ctrl row, where Ctrl stays on). */
    fun sendRaw(tabId: String, bytes: ByteArray) = sessions.send(tabId, bytes)

    fun resize(tabId: String, cols: Int, rows: Int) = sessions.resize(tabId, cols, rows)

    fun output(tabId: String): SharedFlow<OutputChunk>? = sessions.output(tabId)

    fun snapshot(tabId: String): OutputChunk? = sessions.snapshot(tabId)

    /**
     * Uploads [name] ([size] bytes, or -1 when unknown) to the tab's server.
     * [open] is called on an IO thread; it must not hold on to an activity.
     */
    fun startUpload(tabId: String?, name: String, size: Long, open: () -> InputStream) {
        if (_state.value.upload is Upload.Running) return
        if (tabId == null || sessions.state.value.tab(tabId)?.status != ConnectionStatus.CONNECTED) {
            setUpload(Upload.NotConnected)
            return
        }
        setUpload(Upload.Running(name, 0, size))
        viewModelScope.launch {
            val outcome = try {
                withContext(ioDispatcher) {
                    open().use { input ->
                        sessions.upload(tabId, input, name, size) { done, total ->
                            setUpload(Upload.Running(name, done, total))
                        }
                    }
                }
                Upload.Done(tabId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Upload.Failed(SshErrorClassifier.classify(e))
            }
            setUpload(outcome)
        }
    }

    private fun setUpload(upload: Upload?) = _state.update { it.copy(upload = upload) }

    /** The upload's outcome was shown; a running upload is not affected. */
    fun onUploadShown() = _state.update { it.copy(upload = it.upload as? Upload.Running) }

    /** Deletes a just-uploaded file again. */
    fun undoUpload(done: Upload.Done) {
        viewModelScope.launch {
            val outcome = try {
                withContext(ioDispatcher) { sessions.deleteRemote(done.tabId, done.name) }
                Message.UploadUndone(done.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Message.UndoFailed(SshErrorClassifier.classify(e))
            }
            show(outcome)
        }
    }
}
