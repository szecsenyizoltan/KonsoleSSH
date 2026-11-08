package hu.billman.konsolessh.session

import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ssh.SshError

enum class ConnectionStatus { NONE, CONNECTING, CONNECTED, DISCONNECTED }

/** A felhasználótól várt döntés; a felület állapotként látja és válaszol rá. */
sealed interface PendingPrompt {
    val id: Long

    data class Password(override val id: Long, val displayHost: String) : PendingPrompt
    data class HostKey(override val id: Long, val prompt: HostKeyPrompt) : PendingPrompt
}

/** Egy SSH-fül teljes állapota; ez az egyetlen igazságforrás a felület számára. */
data class TabState(
    val id: String,
    val connectionId: String,
    val title: String,
    val displayName: String,
    val host: String,
    val status: ConnectionStatus = ConnectionStatus.NONE,
    /** Kapcsolódási hiba, amíg a felület meg nem jelenítette. */
    val error: SshError? = null,
    val prompt: PendingPrompt? = null,
)

data class SessionsState(val tabs: List<TabState> = emptyList()) {
    val connectedCount: Int get() = tabs.count { it.status == ConnectionStatus.CONNECTED }

    fun tab(id: String): TabState? = tabs.find { it.id == id }
}

/** A terminálba írt, a munkamenet-kezelő által generált szövegek. */
interface TerminalMessages {
    fun connecting(host: String, port: Int): String
    fun jumpProgress(host: String, port: Int): String
    fun jumpOk(host: String, port: Int): String
}
