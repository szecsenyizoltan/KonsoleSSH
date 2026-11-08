package hu.billman.konsolessh.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Short-lived SSH helpers for:
 *   1) testing that a connection works (password OR private key) before
 *      committing the credentials to the saved-connection store;
 *   2) uploading a public key using password auth (OpenSSH / Dropbear via
 *      `authorized_keys`, MikroTik RouterOS via SFTP + `/user ssh-keys import`).
 *
 * Both go through the same jump chain and host key check as the terminal.
 */
class SshKeyProvisioner(private val hostKeyStore: HostKeyStore) {

    enum class ServerType { OPENSSH, MIKROTIK, DROPBEAR, UNKNOWN }

    data class TestOutcome(val serverVersion: String, val serverType: ServerType)

    /** Returns the server banner on success; throws on failure. */
    suspend fun testConnection(
        cfg: ConnectionConfig,
        jumps: List<ConnectionConfig>,
        prompter: SessionPrompter,
    ): TestOutcome = withChain(cfg, jumps, allowKey = true, prompter) { session ->
        val version = session.serverVersion.orEmpty()
        TestOutcome(version, detectServerType(version))
    }

    /** Uploads the public key to the server using password auth. */
    suspend fun uploadPublicKey(
        cfg: ConnectionConfig,
        jumps: List<ConnectionConfig>,
        publicKey: String,
        prompter: SessionPrompter,
    ): ServerType = withChain(cfg, jumps, allowKey = false, prompter) { session ->
        val type = detectServerType(session.serverVersion.orEmpty())
        when (type) {
            ServerType.MIKROTIK -> uploadMikrotik(session, cfg.username, publicKey)
            else -> exec(session, authorizedKeysCommand(publicKey))
        }
        type
    }

    // Jump hosts always use their saved credentials (key included); allowKey
    // only restricts the target, where key upload needs password auth.
    private suspend fun <T> withChain(
        cfg: ConnectionConfig,
        jumps: List<ConnectionConfig>,
        allowKey: Boolean,
        prompter: SessionPrompter,
        block: (Session) -> T,
    ): T = withContext(Dispatchers.IO) {
        val chain = JumpChain.connect(jumps + cfg, open = { hop, host, port, alias ->
            JschConnector.newSession(hop, host, port, alias, hostKeyStore, hop !== cfg || allowKey) {
                ProbeUserInfo(it, prompter)
            }.also { it.connectProbe() }
        })
        try {
            block(chain.last())
        } finally {
            chain.asReversed().forEach { runCatching { it.disconnect() } }
        }
    }

    // The UserInfo never re-prompts for a password, so JSch's "Auth cancel"
    // here really means the server rejected the credentials.
    private fun Session.connectProbe() {
        try {
            connect(JschConnector.CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            val msg = e.message.orEmpty()
            if (!msg.startsWith("Auth cancel")) throw e
            throw JSchException(msg.replaceFirst("Auth cancel", "Auth fail"), e)
        }
    }

    private fun uploadMikrotik(session: Session, username: String, publicKey: String) {
        val remoteFileName = "konsolessh-${username.replace(UNSAFE_FILE_CHARS, "_")}.pub"
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(EXEC_TIMEOUT_MS)
        try {
            sftp.put(publicKey.byteInputStream(Charsets.US_ASCII), remoteFileName, ChannelSftp.OVERWRITE)
        } finally {
            runCatching { sftp.disconnect() }
        }
        val user = username.replace("\\", "\\\\").replace("\"", "\\\"")
        exec(session, "/user ssh-keys import public-key-file=$remoteFileName user=\"$user\"")
    }

    private fun exec(session: Session, command: String) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val errStream = ByteArrayOutputStream()
        channel.setErrStream(errStream)
        val input = channel.inputStream
        channel.connect(EXEC_TIMEOUT_MS)
        try {
            val buf = ByteArray(BUFFER_SIZE)
            val deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS
            while (!channel.isClosed) {
                while (input.available() > 0) input.read(buf)
                if (System.currentTimeMillis() > deadline) throw IOException("remote command timed out")
                Thread.sleep(POLL_MS)
            }
            val exit = channel.exitStatus
            if (exit != 0) {
                val stderr = String(errStream.toByteArray(), Charsets.UTF_8).trim()
                throw IOException(if (stderr.isNotBlank()) "exit=$exit: $stderr" else "exit=$exit")
            }
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun detectServerType(version: String): ServerType = when {
        version.contains("ROSSSH", true) -> ServerType.MIKROTIK
        version.contains("OpenSSH", true) -> ServerType.OPENSSH
        version.contains("dropbear", true) -> ServerType.DROPBEAR
        else -> ServerType.UNKNOWN
    }

    /** Csak a host key kérdést teszi fel; jelszót és passphrase-t nem kér újra. */
    private class ProbeUserInfo(
        private val hostKeys: TofuHostKeyRepository,
        private val prompter: SessionPrompter,
    ) : UserInfo {
        override fun promptYesNo(message: String): Boolean =
            hostKeys.pending?.let(prompter::confirmHostKey) ?: false
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String): Boolean = false
        override fun promptPassphrase(message: String): Boolean = false
        override fun showMessage(message: String) = Unit
    }

    companion object {
        private const val EXEC_TIMEOUT_MS = 10_000
        private const val POLL_MS = 50L
        private const val BUFFER_SIZE = 1024
        private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

        /**
         * Shell command appending [publicKey] to authorized_keys. The key goes
         * in POSIX single quotes, so nothing in it (host or user name in the
         * comment) is interpreted by the remote shell.
         */
        fun authorizedKeysCommand(publicKey: String): String {
            val quoted = "'" + publicKey.replace("'", "'\\''") + "'"
            return "umask 077 && mkdir -p ~/.ssh && printf '%s\\n' $quoted >> ~/.ssh/authorized_keys" +
                " && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
        }
    }
}
