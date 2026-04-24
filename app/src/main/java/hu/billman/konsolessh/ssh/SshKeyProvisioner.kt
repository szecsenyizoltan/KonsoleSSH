package hu.billman.konsolessh.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * Short-lived SSH helpers for:
 *   1) testing that a password-based connection works before committing to
 *      key-based migration;
 *   2) uploading a public key to the server using that working password
 *      (OpenSSH / Dropbear via `authorized_keys`, Mikrotik RouterOS via
 *      SFTP + `/user ssh-keys import`).
 *
 * These functions open their own JSch sessions — they do NOT touch the
 * long-lived SshSession used for interactive terminal traffic.
 */
object SshKeyProvisioner {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val EXEC_TIMEOUT_MS    = 10_000

    enum class ServerType { OPENSSH, MIKROTIK, DROPBEAR, UNKNOWN }

    data class TestOutcome(val serverVersion: String, val serverType: ServerType)

    /** Returns the server version banner on success; throws on failure. */
    suspend fun testPasswordConnection(cfg: ConnectionConfig): TestOutcome =
        withContext(Dispatchers.IO) {
            val session = openPasswordSession(cfg)
            try {
                session.connect(CONNECT_TIMEOUT_MS)
                val version = session.serverVersion ?: ""
                TestOutcome(version, detectServerType(version))
            } finally {
                runCatching { session.disconnect() }
            }
        }

    /** Uploads the public key to the server using the password auth. */
    suspend fun uploadPublicKey(cfg: ConnectionConfig, publicKey: String): ServerType =
        withContext(Dispatchers.IO) {
            val session = openPasswordSession(cfg)
            try {
                session.connect(CONNECT_TIMEOUT_MS)
                val version = session.serverVersion ?: ""
                val type = detectServerType(version)
                when (type) {
                    ServerType.MIKROTIK -> uploadMikrotik(session, cfg.username, publicKey)
                    else                -> uploadUnixLike(session, publicKey)
                }
                type
            } finally {
                runCatching { session.disconnect() }
            }
        }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun openPasswordSession(cfg: ConnectionConfig): Session {
        val jsch = JSch()
        val session = jsch.getSession(cfg.username, cfg.host, cfg.port)
        val props = Properties().apply {
            set("StrictHostKeyChecking", "no")
            set("PreferredAuthentications", "password,keyboard-interactive")
        }
        session.setConfig(props)
        if (cfg.password.isNotBlank()) session.setPassword(cfg.password)
        // No UserInfo — we do not want retry loops or interactive prompts here.
        return session
    }

    private fun detectServerType(version: String): ServerType = when {
        version.contains("ROSSSH",   true) -> ServerType.MIKROTIK
        version.contains("OpenSSH",  true) -> ServerType.OPENSSH
        version.contains("dropbear", true) -> ServerType.DROPBEAR
        else -> ServerType.UNKNOWN
    }

    private fun uploadUnixLike(session: Session, publicKey: String) {
        val escaped = publicKey.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        val cmd = """
            mkdir -p ~/.ssh && \
            printf '%s\n' "$escaped" >> ~/.ssh/authorized_keys && \
            chmod 700 ~/.ssh && \
            chmod 600 ~/.ssh/authorized_keys
        """.trimIndent().replace('\n', ' ')
        exec(session, cmd)
    }

    private fun uploadMikrotik(session: Session, username: String, publicKey: String) {
        val remoteFileName = "konsolessh-$username.pub"
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(EXEC_TIMEOUT_MS)
        try {
            sftp.put(publicKey.byteInputStream(Charsets.US_ASCII), remoteFileName, ChannelSftp.OVERWRITE)
        } finally {
            runCatching { sftp.disconnect() }
        }
        val importCmd = "/user ssh-keys import public-key-file=$remoteFileName user=$username"
        exec(session, importCmd)
    }

    private fun exec(session: Session, command: String) {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val errStream = java.io.ByteArrayOutputStream()
        channel.setErrStream(errStream)
        val input = channel.inputStream
        channel.connect(EXEC_TIMEOUT_MS)
        try {
            val buf = ByteArray(1024)
            val deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS
            while (true) {
                while (input.available() > 0) input.read(buf)
                if (channel.isClosed) break
                if (System.currentTimeMillis() > deadline) break
                Thread.sleep(50)
            }
            val exit = channel.exitStatus
            if (exit != 0 && exit != -1) {
                val stderr = errStream.toString(Charsets.UTF_8).trim()
                throw RuntimeException(
                    if (stderr.isNotBlank()) "exit=$exit: $stderr" else "exit=$exit"
                )
            }
        } finally {
            runCatching { channel.disconnect() }
        }
    }
}
