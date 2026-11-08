package hu.billman.konsolessh.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SshSession(
    private val config: ConnectionConfig,
    private val hostKeyStore: HostKeyStore,
    /** Jump hostok a célig, a legkülső elsőként ([JumpChain.resolve]). */
    private val jumps: List<ConnectionConfig> = emptyList(),
    private val prompter: SessionPrompter = SessionPrompter.Reject,
    private val messages: Messages = Messages.Default,
) : TerminalSession {

    data class Messages(
        val jumpProgress: (host: String, port: Int) -> String,
        val jumpOk: (host: String, port: Int) -> String,
    ) {
        companion object {
            val Default = Messages(
                jumpProgress = { h, p -> "Jump: $h:$p...\r\n" },
                jumpOk = { h, p -> "Jump OK → Connecting: $h:$p...\r\n" },
            )
        }
    }

    companion object {
        private const val SHELL_CONNECT_TIMEOUT_MS = 10_000

        /**
         * SSH keep-alive: no-op csomag küldése ennyi ms-enként. Mobil NAT-
         * tűzfalakon egy session 60–120 másodperc inaktivitás után eldobódik,
         * ezért 30 másodperces alive-intervallum.
         */
        private const val SERVER_ALIVE_INTERVAL_MS = 30_000

        /** Ennyi egymás utáni sikertelen alive-check után a session lezár. */
        private const val SERVER_ALIVE_COUNT_MAX = 3

        private const val CELL_W_PX = 8
        private const val CELL_H_PX = 16
    }

    @Volatile private var jumpSessions: List<Session> = emptyList()

    /** A [disconnect] lefutott; egy még futó [connect] eredményét azonnal lebontja. */
    @Volatile private var closed = false

    @Volatile private var jschSession: Session? = null

    @Volatile private var shellChannel: ChannelShell? = null

    @Volatile private var input: InputStream? = null

    @Volatile private var output: OutputStream? = null

    /**
     * True once the server has rejected at least one credential attempt and
     * JSch has called back for a retry. Lets the error be reported as "bad
     * credentials" instead of JSch's misleading "Auth cancel".
     */
    @Volatile private var sawCredentialReject = false

    // Single serial thread for all writes: keeps keystroke order and stays off the main thread.
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-write").apply { isDaemon = true }
    }

    override val isConnected: Boolean
        get() = jschSession?.isConnected == true && shellChannel?.isConnected == true

    override suspend fun connect(cols: Int, rows: Int, onProgress: suspend (String) -> Unit) =
        withContext(Dispatchers.IO) {
            sawCredentialReject = false
            try {
                val chain = JumpChain.connect(
                    jumps + config,
                    open = ::openHop,
                    onJump = { hop -> onProgress(messages.jumpProgress(hop.host, hop.port)) },
                    onForwarded = { next -> onProgress(messages.jumpOk(next.host, next.port)) },
                )
                // The blocking JSch calls do not notice a cancel or a disconnect()
                // that arrived meanwhile; whatever was built must not outlive them.
                try {
                    openShell(chain, cols, rows)
                    if (closed) throw CancellationException("disconnected while connecting")
                    coroutineContext.ensureActive()
                } catch (e: Throwable) {
                    closeChain(chain)
                    throw e
                }
            } catch (e: com.jcraft.jsch.JSchException) {
                // The server really rejected a credential; JSch's "Auth cancel"
                // would point the user at the wrong cause.
                if (sawCredentialReject && e.message.orEmpty().contains("Auth cancel", ignoreCase = true)) {
                    throw com.jcraft.jsch.JSchException("bad credentials: ${e.message}", e)
                }
                throw e
            }
        }

    private fun openShell(chain: List<Session>, cols: Int, rows: Int) {
        jumpSessions = chain.dropLast(1)
        val session = chain.last()
        jschSession = session
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color")
        channel.setPtySize(cols, rows, cols * CELL_W_PX, rows * CELL_H_PX)
        input = channel.inputStream
        output = channel.outputStream
        channel.connect(SHELL_CONNECT_TIMEOUT_MS)
        shellChannel = channel
    }

    // Innermost first: each jump carries the tunnel of the one after it.
    private fun closeChain(chain: List<Session>) {
        runCatching { shellChannel?.disconnect() }
        chain.asReversed().forEach { runCatching { it.disconnect() } }
        shellChannel = null
        jschSession = null
        jumpSessions = emptyList()
        input = null
        output = null
    }

    override fun read(buffer: ByteArray): Int = input?.read(buffer) ?: -1

    override fun send(bytes: ByteArray) {
        if (writer.isShutdown) return
        writer.execute {
            try {
                output?.write(bytes)
                output?.flush()
            } catch (e: java.io.IOException) {
                android.util.Log.e("SshSession", "send failed: ${e.message}", e)
            }
        }
    }

    override fun resize(cols: Int, rows: Int) {
        if (writer.isShutdown) return
        writer.execute {
            runCatching { shellChannel?.setPtySize(cols, rows, cols * CELL_W_PX, rows * CELL_H_PX) }
        }
    }

    /**
     * Upload a file over SFTP into the user's home directory. [size] scales the
     * progress when the server does not report the length; negative = unknown.
     */
    override suspend fun upload(
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ) = withSftp { sftp ->
        val monitor = object : SftpProgressMonitor {
            private var total: Long = size.coerceAtLeast(0L)
            private var transferred: Long = 0L
            override fun init(op: Int, src: String?, dest: String?, max: Long) {
                if (max > 0) total = max
                transferred = 0L
                onProgress(transferred, total)
            }
            override fun count(count: Long): Boolean {
                transferred += count
                onProgress(transferred, total)
                return true
            }
            override fun end() {
                if (total <= 0L) total = transferred
                onProgress(total, total)
            }
        }
        sftp.put(input, remoteName, monitor, ChannelSftp.OVERWRITE)
    }

    /** Delete a file on the remote side via SFTP; the path is relative to the SFTP home. */
    override suspend fun deleteRemote(path: String) = withSftp { it.rm(path) }

    private suspend fun withSftp(block: (ChannelSftp) -> Unit) = withContext(Dispatchers.IO) {
        val session = jschSession ?: throw IllegalStateException("SSH session is not connected")
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(SHELL_CONNECT_TIMEOUT_MS)
        try {
            block(sftp)
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    override fun disconnect() {
        closed = true
        writer.shutdown()
        closeChain(jumpSessions + listOfNotNull(jschSession))
    }

    private fun openHop(hop: ConnectionConfig, host: String, port: Int, alias: String?): Session {
        val session = JschConnector.newSession(hop, host, port, alias, hostKeyStore, allowKey = true) {
            JschUserInfo("${hop.username}@${hop.host}", it)
        }
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
        session.serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
        session.connect(JschConnector.CONNECT_TIMEOUT_MS)
        return session
    }

    private inner class JschUserInfo(
        private val displayHost: String,
        private val hostKeys: TofuHostKeyRepository,
    ) : UserInfo, UIKeyboardInteractive {
        private var pendingPassword: String? = null

        override fun promptPassword(message: String): Boolean {
            // JSch only asks after the stored password failed or when none was
            // stored; either way the server has rejected something.
            sawCredentialReject = true
            pendingPassword = prompter.password(displayHost) ?: return false
            return true
        }

        override fun getPassword(): String? = pendingPassword

        // JSch only asks yes/no about an unknown or changed host key.
        override fun promptYesNo(message: String): Boolean =
            hostKeys.pending?.let(prompter::confirmHostKey) ?: false

        override fun getPassphrase(): String? = null
        override fun promptPassphrase(message: String): Boolean = false
        override fun showMessage(message: String) = Unit

        override fun promptKeyboardInteractive(
            destination: String,
            name: String,
            instruction: String,
            prompt: Array<String>,
            echo: BooleanArray,
        ): Array<String>? {
            // A cancelled answer stops the round: a partial reply fails the login anyway.
            val answers = mutableListOf<String>()
            repeat(prompt.size) { answers += prompter.password(displayHost) ?: return null }
            return answers.toTypedArray()
        }
    }
}
