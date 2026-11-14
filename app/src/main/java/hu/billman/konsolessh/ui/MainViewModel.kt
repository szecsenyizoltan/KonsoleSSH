package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.diagnostics.ProblemReports
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.OutputChunk
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.session.SessionManager
import hu.billman.konsolessh.session.SessionsState
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.terminal.KeyModifiers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * The main screen's ViewModel. The tabs' state comes from the
 * [SessionManager], its single source; this holds only what belongs to the
 * screen, such as the sticky modifiers and the events shown once.
 */
class MainViewModel(
    private val sessions: SessionManager,
    private val repository: ConnectionRepository,
    private val settings: SettingsRepository,
    private val problemReports: ProblemReports,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val sessionsState: StateFlow<SessionsState> = sessions.state

    val modifiers = KeyModifiers()

    private val _selectTab = MutableStateFlow<String?>(null)

    /** A freshly opened tab the screen should switch to. */
    val selectTab: StateFlow<String?> = _selectTab.asStateFlow()

    /** A question the user must answer before something is closed. */
    sealed interface Confirm {
        data class CloseTab(val tabId: String, val title: String) : Confirm
        data class CloseApp(val connected: Int) : Confirm
    }

    /** Short one-time notices. */
    sealed interface Message {
        data class Error(val error: SshError) : Message
        data object NoProblemReport : Message
        data class UploadUndone(val name: String) : Message
        data class UndoFailed(val reason: String) : Message
    }

    /**
     * A file upload to the home directory of a tab's server. It runs in the
     * ViewModel, so a rotation does not cancel it.
     */
    sealed interface Upload {
        data class Running(val name: String, val done: Long, val total: Long) : Upload
        data class Done(val tabId: String, val name: String) : Upload
        data class Failed(val reason: String) : Upload
        data object NotConnected : Upload
    }

    /** A host key or password question, and the tab it belongs to. */
    data class TabPrompt(val tabId: String, val prompt: PendingPrompt)

    /** A failed connect, shown once; the tab closes after it unless the user acts. */
    data class ConnectError(val tabId: String, val error: SshError)

    private val _confirm = MutableStateFlow<Confirm?>(null)
    val confirm: StateFlow<Confirm?> = _confirm.asStateFlow()

    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    private val _reportToShare = MutableStateFlow<File?>(null)

    /** A problem report the user asked to share. */
    val reportToShare: StateFlow<File?> = _reportToShare.asStateFlow()

    private val _upload = MutableStateFlow<Upload?>(null)
    val upload: StateFlow<Upload?> = _upload.asStateFlow()

    private val _exit = MutableStateFlow(false)

    /** All sessions are closed; the screen should finish the app. */
    val exit: StateFlow<Boolean> = _exit.asStateFlow()

    /** The question to show: one at a time, the first tab's that has one. */
    val activePrompt: StateFlow<TabPrompt?> = sessions.state.derive { state ->
        state.tabs.firstNotNullOfOrNull { tab -> tab.prompt?.let { TabPrompt(tab.id, it) } }
    }

    val connectErrors: StateFlow<List<ConnectError>> = sessions.state.derive { state ->
        state.tabs.mapNotNull { tab -> tab.error?.let { ConnectError(tab.id, it) } }
    }

    /**
     * Whether a session needs the foreground service. The screen starts it when
     * this turns true; the service stops itself when no session is left.
     */
    val serviceNeeded: StateFlow<Boolean> = sessions.state.derive { it.activeCount > 0 }

    private fun <T> StateFlow<SessionsState>.derive(transform: (SessionsState) -> T): StateFlow<T> =
        map(transform).distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, transform(value))

    private val _problemReport = MutableStateFlow<File?>(null)

    /** The last run's ANR or crash report, until the user decides about it. */
    val problemReport: StateFlow<File?> = _problemReport.asStateFlow()

    init {
        viewModelScope.launch { _problemReport.value = problemReports.collectNew() }
    }

    fun onProblemReportHandled() {
        _problemReport.value = null
        viewModelScope.launch { problemReports.markSeen() }
    }

    fun shareLatestProblemReport() {
        viewModelScope.launch {
            val report = problemReports.latest()
            if (report == null) _message.value = Message.NoProblemReport else _reportToShare.value = report
        }
    }

    fun onReportShared() {
        _reportToShare.value = null
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onConfirmShown() {
        _confirm.value = null
    }

    /** Closing a connected tab needs a confirmation; any other tab closes at once. */
    fun requestCloseTab(tabId: String) {
        val tab = sessions.state.value.tab(tabId) ?: return
        if (tab.status == ConnectionStatus.CONNECTED) {
            _confirm.value = Confirm.CloseTab(tabId, tab.title)
        } else {
            closeTab(tabId)
        }
    }

    /** Exiting with live connections needs a confirmation. */
    fun requestCloseApp() {
        val connected = sessions.state.value.connectedCount
        if (connected == 0) exitApp() else _confirm.value = Confirm.CloseApp(connected)
    }

    fun confirmed(confirm: Confirm) = when (confirm) {
        is Confirm.CloseTab -> closeTab(confirm.tabId)
        is Confirm.CloseApp -> exitApp()
    }

    private fun exitApp() {
        sessions.closeAll()
        _exit.value = true
    }

    private val _pickerExpanded = MutableStateFlow<Set<String>>(emptySet())

    /** Group paths open in the tab picker; kept while the activity lives, so reopening the picker keeps them. */
    val pickerExpanded: StateFlow<Set<String>> = _pickerExpanded.asStateFlow()

    fun togglePickerGroup(path: String) = _pickerExpanded.update { if (path in it) it - path else it + path }

    /** Saved terminal font size in sp; 0 until loaded, or when none was saved. */
    val fontSizeSp: StateFlow<Float> = settings.fontSizeSp.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    fun saveFontSize(sp: Float) {
        viewModelScope.launch { settings.setFontSizeSp(sp) }
    }

    fun openTab(connectionId: String) {
        viewModelScope.launch {
            val config = try {
                repository.get(connectionId)
            } catch (e: ConnectionStoreException) {
                _message.value = Message.Error(SshError.StoreUnreadable)
                return@launch
            }
            if (config == null) {
                _message.value = Message.Error(SshError.ConnectionMissing)
            } else {
                _selectTab.value = sessions.openTab(config)
            }
        }
    }

    fun onTabSelected() {
        _selectTab.value = null
    }

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
        if (_upload.value is Upload.Running) return
        if (tabId == null || sessions.state.value.tab(tabId)?.status != ConnectionStatus.CONNECTED) {
            _upload.value = Upload.NotConnected
            return
        }
        _upload.value = Upload.Running(name, 0, size)
        viewModelScope.launch {
            _upload.value = try {
                withContext(ioDispatcher) {
                    open().use { input ->
                        sessions.upload(tabId, input, name, size) { done, total ->
                            _upload.value = Upload.Running(name, done, total)
                        }
                    }
                }
                Upload.Done(tabId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Upload.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** The upload's outcome was shown; a running upload is not affected. */
    fun onUploadShown() = _upload.update { it as? Upload.Running }

    /** Deletes a just-uploaded file again. */
    fun undoUpload(done: Upload.Done) {
        viewModelScope.launch {
            _message.value = try {
                withContext(ioDispatcher) { sessions.deleteRemote(done.tabId, done.name) }
                Message.UploadUndone(done.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Message.UndoFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
