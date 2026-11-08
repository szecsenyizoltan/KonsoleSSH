package hu.billman.konsolessh.ssh

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.TimeUnit

class SshSession(
    private val config: ConnectionConfig,
    private val hostKeyStore: HostKeyStore,
    /** Jump hostok a célig, a legkülső elsőként ([JumpChain.resolve]). */
    private val jumps: List<ConnectionConfig> = emptyList(),
    private val messages: Messages = Messages.Default
) {

    data class Messages(
        val jumpProgress: (host: String, port: Int) -> String,
        val jumpOk: (host: String, port: Int) -> String
    ) {
        companion object {
            val Default = Messages(
                jumpProgress = { h, p -> "Jump: $h:$p...\r\n" },
                jumpOk       = { h, p -> "Jump OK → Connecting: $h:$p...\r\n" }
            )
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS       = 15_000
        private const val SHELL_CONNECT_TIMEOUT_MS = 10_000
        private const val PASSWORD_PROMPT_TIMEOUT_S = 30L

        /**
         * SSH keep-alive: no-op csomag küldése ennyi ms-enként. Mobil NAT-
         * tűzfalakon egy session 60–120 másodperc inaktivitás után eldobódik,
         * ezért 30 másodperces alive-intervallum.
         */
        private const val SERVER_ALIVE_INTERVAL_MS = 30_000

        /** Ennyi egymás utáni sikertelen alive-check után a session lezár. */
        private const val SERVER_ALIVE_COUNT_MAX = 3
    }

    private fun Session.applyKeepAlive() {
        setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
        serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
    }

    private var jumpSessions: List<Session> = emptyList()
    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null

    // Called on main thread: displayHost is the real server (e.g. "csaba@10.1.1.8"), callback returns entered password or null
    var passwordPrompter: ((displayHost: String, callback: (String?) -> Unit) -> Unit)? = null

    // Called on main thread with an unknown or changed host key; callback true accepts it
    var hostKeyPrompter: HostKeyPrompterFn? = null

    /**
     * True once the server has rejected at least one credential attempt and
     * JSch has called back for a retry. Lets the outer catch block replace
     * the generic "Auth cancel" error with a targeted "bad credentials" one.
     */
    @Volatile private var sawCredentialReject = false

    private inner class JschUserInfo(
        private val displayHost: String,
        private val hostKeys: TofuHostKeyRepository,
    ) : UserInfo, UIKeyboardInteractive {
        private var pendingPassword: String? = null

        private fun askUser(): String? {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: String? = null
            Handler(Looper.getMainLooper()).post {
                passwordPrompter?.invoke(displayHost) { answer ->
                    result = answer
                    latch.countDown()
                } ?: latch.countDown()
            }
            if (!latch.await(PASSWORD_PROMPT_TIMEOUT_S, TimeUnit.SECONDS)) return null
            return result
        }

        override fun promptPassword(message: String): Boolean {
            // JSch only calls this after the initial stored password failed,
            // or when no password was stored. In either case, the server has
            // actively rejected something — remember that for error routing.
            sawCredentialReject = true
            pendingPassword = askUser() ?: return false
            return true
        }
        override fun getPassword(): String? = pendingPassword

        // JSch only asks yes/no about an unknown or changed host key
        override fun promptYesNo(message: String): Boolean = hostKeys.confirmPending(hostKeyPrompter)
        override fun getPassphrase(): String? = null
        override fun promptPassphrase(message: String): Boolean = false
        override fun showMessage(message: String) {}
        override fun promptKeyboardInteractive(
            destination: String, name: String, instruction: String,
            prompt: Array<String>, echo: BooleanArray
        ): Array<String>? {
            if (prompt.isEmpty()) return emptyArray()
            return Array(prompt.size) { i -> askUser() ?: return null }
        }
    }

    // Single serial thread for all writes — avoids race conditions and NetworkOnMainThreadException
    private val writeThread = HandlerThread("ssh-write").also { it.start() }
    private val writeHandler = Handler(writeThread.looper)

    var inputStream: InputStream? = null
        private set
    var outputStream: OutputStream? = null
        private set

    val isConnected: Boolean
        get() = jschSession?.isConnected == true && shellChannel?.isConnected == true

    suspend fun connect(
        termCols: Int = 80,
        termRows: Int = 24,
        onProgress: (String) -> Unit = {},
        onConnected: () -> Unit,
        onError: (Throwable) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            sawCredentialReject = false
            val props = Properties().apply {
                // Trust on first use: unknown or changed host keys go to the user,
                // accepted ones are checked on every later connection.
                set("StrictHostKeyChecking", "ask")
                // Re-enable legacy ssh-rsa (SHA-1 RSA) as a last-resort host key
                // algorithm. JSch 0.2.x dropped it by default, but many consumer
                // routers and embedded SSH servers still only offer it.
                set(
                    "server_host_key",
                    "ssh-ed25519,ecdsa-sha2-nistp521,ecdsa-sha2-nistp384," +
                    "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa"
                )
            }

            // Direct connection = a chain with zero jumps.
            val chain = JumpChain.connect(
                jumps + config,
                open = { hop, host, port, alias -> openHop(hop, host, port, alias, props) },
                onJump = { hop ->
                    withContext(Dispatchers.Main) { onProgress(messages.jumpProgress(hop.host, hop.port)) }
                },
                onForwarded = { next ->
                    withContext(Dispatchers.Main) { onProgress(messages.jumpOk(next.host, next.port)) }
                },
            )
            jumpSessions = chain.dropLast(1)
            jschSession = chain.last()

            val channel = jschSession!!.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color")
            channel.setPtySize(termCols, termRows, termCols * 8, termRows * 16)

            inputStream = channel.inputStream
            outputStream = channel.outputStream

            channel.connect(SHELL_CONNECT_TIMEOUT_MS)
            shellChannel = channel

            withContext(Dispatchers.Main) { onConnected() }
        } catch (e: Throwable) {
            val rewrapped = if (sawCredentialReject &&
                (e.message ?: "").contains("Auth cancel", ignoreCase = true)
            ) {
                // The server actually rejected a credential attempt; JSch's
                // "Auth cancel" is misleading here. Annotate so friendlyError
                // can show the "wrong username or password" message instead of
                // the Mikrotik-style key-only hint.
                RuntimeException("bad credentials: ${e.message}", e)
            } else e
            withContext(Dispatchers.Main) { onError(rewrapped) }
        }
    }

    fun resize(cols: Int, rows: Int) {
        writeHandler.post {
            try { shellChannel?.setPtySize(cols, rows, cols * 8, rows * 16) } catch (_: Exception) {}
        }
    }

    fun sendInput(text: String) = sendBytes(text.toByteArray(Charsets.UTF_8))

    fun sendBytes(bytes: ByteArray) {
        writeHandler.post {
            try {
                outputStream?.write(bytes)
                outputStream?.flush()
            } catch (e: Exception) {
                android.util.Log.e("SshSession", "sendBytes error: ${e.message}", e)
            }
        }
    }

    /**
     * Upload a file over SFTP into the user's home directory. `fileSize` is
     * used for progress scaling when the stream's real length isn't reported
     * by the server; pass a negative value for "unknown".
     * Returns the remote directory the file was written into ("~"). Runs on IO.
     */
    suspend fun upload(
        input: InputStream,
        remoteName: String,
        fileSize: Long,
        onProgress: (transferred: Long, total: Long) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val session = jschSession
            ?: throw IllegalStateException("SSH session is not connected")
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(SHELL_CONNECT_TIMEOUT_MS)
        try {
            val monitor = object : com.jcraft.jsch.SftpProgressMonitor {
                private var total: Long = fileSize.coerceAtLeast(0L)
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
            "~"
        } finally {
            try { sftp.disconnect() } catch (_: Exception) {}
        }
    }

    /** Delete a file on the remote side via SFTP. Path is relative to the SFTP home. Runs on IO. */
    suspend fun deleteRemote(remotePath: String) = withContext(Dispatchers.IO) {
        val session = jschSession
            ?: throw IllegalStateException("SSH session is not connected")
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(SHELL_CONNECT_TIMEOUT_MS)
        try {
            sftp.rm(remotePath)
        } finally {
            try { sftp.disconnect() } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        writeThread.quitSafely()
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        // Innermost first: each jump carries the tunnel of the one after it.
        jumpSessions.asReversed().forEach { try { it.disconnect() } catch (_: Exception) {} }
        shellChannel = null
        jschSession = null
        jumpSessions = emptyList()
        inputStream = null
        outputStream = null
    }

    private fun openHop(
        hop: ConnectionConfig,
        host: String,
        port: Int,
        alias: String?,
        props: Properties,
    ): Session {
        val jsch = JSch()
        val hostKeys = jsch.useTofu()
        if (hop.privateKey.isNotBlank()) {
            val pp = hop.privateKeyPassphrase.takeIf { it.isNotBlank() }?.toByteArray()
            jsch.addIdentity("key_${hop.id}", hop.privateKey.toByteArray(), null, pp)
        }
        val session = jsch.getSession(hop.username, host, port)
        // Behind a jump the session goes to 127.0.0.1:<random port>; the alias
        // keeps the host key under the real name so it is not "new" every time.
        alias?.let { session.setHostKeyAlias(it) }
        session.setConfig(props)
        session.setConfig("PreferredAuthentications", buildPreferredAuths(hop))
        if (hop.password.isNotBlank()) session.setPassword(hop.password)
        session.userInfo = JschUserInfo("${hop.username}@${hop.host}", hostKeys)
        session.applyKeepAlive()
        session.connect(CONNECT_TIMEOUT_MS)
        return session
    }

    private fun JSch.useTofu(): TofuHostKeyRepository =
        TofuHostKeyRepository(hostKeyStore).also { hostKeyRepository = it }

    private fun buildPreferredAuths(cfg: ConnectionConfig): String {
        val hasPassword = cfg.password.isNotBlank()
        val hasKey      = cfg.privateKey.isNotBlank()
        return when {
            hasPassword && hasKey -> "publickey,password,keyboard-interactive"
            hasKey                -> "publickey"
            else                  -> "password,keyboard-interactive"
        }
    }
}
