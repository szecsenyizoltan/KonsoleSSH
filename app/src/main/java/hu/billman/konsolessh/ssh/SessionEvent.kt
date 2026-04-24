package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.ui.ConnectionStatus

/**
 * SSH-session-eseményhiearchia a Flow-alapú megfigyelőkhöz.
 *
 * A SshForegroundService mostantól egy SharedFlow<SessionEvent>-et is
 * exponál tabonként (a korábbi 3 egyirányú callback helyett: dataListener,
 * statusListener, connectErrorListener). A régi callback-setterek
 * backward-compat miatt tovább működnek (@Deprecated).
 *
 * A PasswordRequest szándékosan NEM része az eseményhiearchiának — az
 * request/response szemantikájú (a UI blokkoló választ ad), ezért külön
 * csatornán, a setPasswordPrompter-en keresztül marad.
 */
sealed class SessionEvent {

    /**
     * PTY-bájtok érkeztek a szerverről (vagy a Service által generált
     * infóüzenet, pl. "Connecting: host:port…"). A [bytes] tartalma nem
     * módosítandó.
     */
    class Data(val bytes: ByteArray) : SessionEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "SessionEvent.Data(size=${bytes.size})"
    }

    /** Kapcsolati állapot változott (CONNECTING, CONNECTED, DISCONNECTED, NONE). */
    data class StatusChange(val status: ConnectionStatus) : SessionEvent()

    /**
     * Csatlakozási hiba felhasználóbarát üzenettel. Akkor fut, amikor a
     * `SshSession.connect` onError-on keresztül szolgáltatási hibát jelez.
     */
    data class ConnectError(val message: String) : SessionEvent()
}
