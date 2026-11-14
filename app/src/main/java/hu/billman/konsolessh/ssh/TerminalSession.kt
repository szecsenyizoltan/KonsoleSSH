package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.model.ConnectionConfig
import java.io.InputStream

/**
 * An interactive shell session. [SshSession] is the real one; the
 * interface lets the session handling be tested without a network.
 */
interface TerminalSession {
    val isConnected: Boolean

    /** Connects, through the jump chain if there is one; throws on failure. */
    suspend fun connect(cols: Int, rows: Int, onProgress: suspend (String) -> Unit)

    /** A blocking read from the shell channel; -1 at the end of the stream. */
    fun read(buffer: ByteArray): Int

    fun send(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    suspend fun upload(
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    )

    suspend fun deleteRemote(path: String)

    fun disconnect()
}

fun interface TerminalSessionFactory {
    fun create(
        config: ConnectionConfig,
        jumps: List<ConnectionConfig>,
        prompter: SessionPrompter,
    ): TerminalSession
}

/**
 * The user's decisions while connecting. Called blocking, on the I/O
 * thread, as JSch requires; the answer comes from the UI.
 */
interface SessionPrompter {
    /** null: the user cancelled, or the time ran out. */
    fun password(displayHost: String): String?

    fun confirmHostKey(prompt: HostKeyPrompt): Boolean

    companion object {
        /** Without a UI: gives no password and accepts no unknown key. */
        val Reject: SessionPrompter = object : SessionPrompter {
            override fun password(displayHost: String): String? = null
            override fun confirmHostKey(prompt: HostKeyPrompt): Boolean = false
        }
    }
}
