package hu.szecsenyi.konsolessh.ssh

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.TimeUnit

class SshSession(
    private val config: ConnectionConfig,
    private val jumpConfig: ConnectionConfig? = null,
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
    }

    private var jumpSession: Session? = null
    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null

    // Called on main thread: displayHost is the real server (e.g. "csaba@10.1.1.8"), callback returns entered password or null
    var passwordPrompter: ((displayHost: String, callback: (String?) -> Unit) -> Unit)? = null

    private inner class JschUserInfo(private val displayHost: String) : UserInfo, UIKeyboardInteractive {
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
            pendingPassword = askUser() ?: return false
            return true
        }
        override fun getPassword(): String? = pendingPassword
        override fun promptYesNo(message: String): Boolean = true
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
            val props = Properties().apply {
                set("StrictHostKeyChecking", "no")
            }

            // ── Jump host (SSH -J) ────────────────────────────────────────────
            if (jumpConfig != null) {
                val jumpJsch = JSch()
                if (jumpConfig.privateKey.isNotBlank()) {
                    val kb = jumpConfig.privateKey.toByteArray()
                    val pp = if (jumpConfig.privateKeyPassphrase.isNotBlank()) jumpConfig.privateKeyPassphrase.toByteArray() else null
                    jumpJsch.addIdentity("jump_${jumpConfig.id}", kb, null, pp)
                }
                val js = jumpJsch.getSession(jumpConfig.username, jumpConfig.host, jumpConfig.port)
                js.setConfig(props)
                js.setConfig("PreferredAuthentications", buildPreferredAuths(jumpConfig))
                if (jumpConfig.password.isNotBlank()) js.setPassword(jumpConfig.password)
                js.userInfo = JschUserInfo("${jumpConfig.username}@${jumpConfig.host}")
                withContext(Dispatchers.Main) {
                    onProgress(messages.jumpProgress(jumpConfig.host, jumpConfig.port))
                }
                js.connect(CONNECT_TIMEOUT_MS)
                jumpSession = js
                // Local port forward through jump to target
                val localPort = js.setPortForwardingL(0, config.host, config.port)
                withContext(Dispatchers.Main) {
                    onProgress(messages.jumpOk(config.host, config.port))
                }

                val jsch = JSch()
                if (config.privateKey.isNotBlank()) {
                    val keyBytes = config.privateKey.toByteArray()
                    val pp = if (config.privateKeyPassphrase.isNotBlank()) config.privateKeyPassphrase.toByteArray() else null
                    jsch.addIdentity("key_${config.id}", keyBytes, null, pp)
                }
                val session = jsch.getSession(config.username, "127.0.0.1", localPort)
                session.setConfig(props)
                session.setConfig("PreferredAuthentications", buildPreferredAuths(config))
                if (config.password.isNotBlank()) session.setPassword(config.password)
                session.userInfo = JschUserInfo("${config.username}@${config.host}")
                session.connect(CONNECT_TIMEOUT_MS)
                jschSession = session

            } else {
                // ── Direct connection ─────────────────────────────────────────
                val jsch = JSch()
                if (config.privateKey.isNotBlank()) {
                    val keyBytes = config.privateKey.toByteArray()
                    val pp = if (config.privateKeyPassphrase.isNotBlank()) config.privateKeyPassphrase.toByteArray() else null
                    jsch.addIdentity("key_${config.id}", keyBytes, null, pp)
                }
                val session = jsch.getSession(config.username, config.host, config.port)
                props["PreferredAuthentications"] = buildPreferredAuths(config)
                session.setConfig(props)
                if (config.password.isNotBlank()) session.setPassword(config.password)
                session.userInfo = JschUserInfo("${config.username}@${config.host}")
                session.connect(CONNECT_TIMEOUT_MS)
                jschSession = session
            }

            val channel = jschSession!!.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color")
            channel.setPtySize(termCols, termRows, termCols * 8, termRows * 16)

            inputStream = channel.inputStream
            outputStream = channel.outputStream

            channel.connect(SHELL_CONNECT_TIMEOUT_MS)
            shellChannel = channel

            withContext(Dispatchers.Main) { onConnected() }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { onError(e) }
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

    fun disconnect() {
        writeThread.quitSafely()
        try { shellChannel?.disconnect() } catch (_: Exception) {}
        try { jschSession?.disconnect() } catch (_: Exception) {}
        try { jumpSession?.disconnect() } catch (_: Exception) {}
        shellChannel = null
        jschSession = null
        jumpSession = null
        inputStream = null
        outputStream = null
    }

    private fun buildPreferredAuths(cfg: ConnectionConfig): String {
        val hasPassword = cfg.password.isNotBlank()
        val hasKey      = cfg.privateKey.isNotBlank()
        return when {
            hasPassword && hasKey -> "password,keyboard-interactive,publickey"
            hasKey                -> "publickey"
            else                  -> "password,keyboard-interactive"
        }
    }
}
