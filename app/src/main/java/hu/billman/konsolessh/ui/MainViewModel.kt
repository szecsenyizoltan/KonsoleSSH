package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.OutputChunk
import hu.billman.konsolessh.session.SessionManager
import hu.billman.konsolessh.session.SessionsState
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.terminal.KeyModifiers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) : ViewModel() {

    val sessionsState: StateFlow<SessionsState> = sessions.state

    val modifiers = KeyModifiers()

    private val _selectTab = MutableStateFlow<String?>(null)

    /** Frissen nyitott fül, amelyre a felületnek át kell váltania. */
    val selectTab: StateFlow<String?> = _selectTab.asStateFlow()

    private val _storeError = MutableStateFlow<SshError?>(null)
    val storeError: StateFlow<SshError?> = _storeError.asStateFlow()

    var fontSizeSp: Float
        get() = settings.fontSizeSp
        set(value) {
            settings.fontSizeSp = value
        }

    fun openTab(connectionId: String) {
        viewModelScope.launch {
            val config = try {
                repository.get(connectionId)
            } catch (e: ConnectionStoreException) {
                _storeError.value = SshError.StoreUnreadable
                return@launch
            }
            if (config == null) {
                _storeError.value = SshError.ConnectionMissing
            } else {
                _selectTab.value = sessions.openTab(config)
            }
        }
    }

    fun onTabSelected() {
        _selectTab.value = null
    }

    fun onStoreErrorShown() {
        _storeError.value = null
    }

    /** Csak az új fület kapcsolja; egy megszakadt kapcsolat a felhasználóra vár. */
    fun connectIfNew(tabId: String, cols: Int, rows: Int) {
        if (sessions.state.value.tab(tabId)?.status == ConnectionStatus.NONE) sessions.connect(tabId, cols, rows)
    }

    fun reconnect(tabId: String, cols: Int, rows: Int) = sessions.connect(tabId, cols, rows, isReconnect = true)

    fun closeTab(tabId: String) = sessions.closeTab(tabId)

    fun closeAll() = sessions.closeAll()

    fun renameTab(tabId: String, title: String) = sessions.renameTab(tabId, title)

    fun onErrorShown(tabId: String) = sessions.clearError(tabId)

    fun answerPrompt(tabId: String, promptId: Long, value: Any?) = sessions.answerPrompt(tabId, promptId, value)

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
