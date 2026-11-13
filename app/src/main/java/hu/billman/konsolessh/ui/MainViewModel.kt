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
import java.io.File
import java.io.InputStream

/**
 * A főképernyő ViewModelje. A fülek állapota a [SessionManager]-ből jön
 * (egyetlen forrás); itt csak a képernyőhöz tartozó állapot él: a ragadós
 * módosítók, a kiválasztandó fül és az egyszer megjelenítendő hiba.
 */
class MainViewModel(
    private val sessions: SessionManager,
    private val repository: ConnectionRepository,
    private val settings: SettingsRepository,
    private val problemReports: ProblemReports,
) : ViewModel() {

    val sessionsState: StateFlow<SessionsState> = sessions.state

    val modifiers = KeyModifiers()

    private val _selectTab = MutableStateFlow<String?>(null)

    /** Frissen nyitott fül, amelyre a felületnek át kell váltania. */
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

    /** Az előző futás ANR- vagy összeomlás-jelentése, amíg a felhasználó nem döntött róla. */
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

    /** Csak az új fület kapcsolja; egy megszakadt kapcsolat a felhasználóra vár. */
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

    /** Felhasználói bevitel: a ragadós módosítók itt érvényesülnek, majd visszaállnak. */
    fun send(tabId: String, bytes: ByteArray) {
        if (bytes.isNotEmpty()) sessions.send(tabId, modifiers.consume(bytes))
    }

    /** Vezérlőkód a módosítók érintése nélkül (Ctrl-sor: a Ctrl aktív marad). */
    fun sendRaw(tabId: String, bytes: ByteArray) = sessions.send(tabId, bytes)

    fun resize(tabId: String, cols: Int, rows: Int) = sessions.resize(tabId, cols, rows)

    fun output(tabId: String): SharedFlow<OutputChunk>? = sessions.output(tabId)

    fun snapshot(tabId: String): OutputChunk? = sessions.snapshot(tabId)

    fun isConnected(tabId: String): Boolean =
        sessions.state.value.tab(tabId)?.status == ConnectionStatus.CONNECTED

    suspend fun upload(
        tabId: String,
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ) = sessions.upload(tabId, input, remoteName, size, onProgress)

    suspend fun deleteRemote(tabId: String, path: String) = sessions.deleteRemote(tabId, path)
}
