package hu.billman.konsolessh.session

import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ssh.SshError

enum class ConnectionStatus { NONE, CONNECTING, CONNECTED, DISCONNECTED }

/** A decision the session waits for; the UI sees it as state and answers it. */
sealed interface PendingPrompt {
    val id: Long

    data class Password(override val id: Long, val displayHost: String) : PendingPrompt
    data class HostKey(override val id: Long, val prompt: HostKeyPrompt) : PendingPrompt
}

/** The answer to a [PendingPrompt]; its kind must match the question's. */
sealed interface PromptAnswer {
    /** null: the user declined to give a password. */
    data class Password(val password: String?) : PromptAnswer
    data class HostKey(val accept: Boolean) : PromptAnswer
}

/** The whole state of one SSH tab; the UI's single source of truth. */
data class TabState(
    val id: String,
    val connectionId: String,
    val title: String,
    val displayName: String,
    val host: String,
    val status: ConnectionStatus = ConnectionStatus.NONE,
    /** A connection error, until the UI has shown it. */
    val error: SshError? = null,
    val prompt: PendingPrompt? = null,
)

data class SessionsState(val tabs: List<TabState> = emptyList()) {
    val connectedCount: Int get() = tabs.count { it.status == ConnectionStatus.CONNECTED }

    /** Tabs connecting or connected: the foreground service is needed while there are any. */
    val activeCount: Int
        get() = tabs.count { it.status == ConnectionStatus.CONNECTING || it.status == ConnectionStatus.CONNECTED }

    fun tab(id: String): TabState? = tabs.find { it.id == id }
}

/** Texts the session manager writes into the terminal itself. */
interface TerminalMessages {
    fun connecting(host: String, port: Int): String
    fun jumpProgress(host: String, port: Int): String
    fun jumpOk(host: String, port: Int): String
}
