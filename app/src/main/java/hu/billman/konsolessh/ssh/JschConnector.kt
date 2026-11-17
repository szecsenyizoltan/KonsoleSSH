package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import java.util.Properties

/**
 * The shared setup of one hop's JSch session. The terminal and the
 * short-lived connections (test, key upload) all use it.
 */
internal object JschConnector {

    const val CONNECT_TIMEOUT_MS = 15_000

    // Legacy ssh-rsa (SHA-1 RSA) is kept last for old routers / embedded
    // servers; JSch 0.2.x dropped it by default.
    private const val SERVER_HOST_KEYS =
        "ssh-ed25519,ecdsa-sha2-nistp521,ecdsa-sha2-nistp384," +
            "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa"

    fun preferredAuths(useKey: Boolean): String = if (useKey) "publickey" else "password,keyboard-interactive"

    /**
     * Creates and configures the session without connecting it. Behind a
     * jump host, [host] and [port] are the local forwarded port and [alias]
     * is the real name, so the host key is not stored under
     * 127.0.0.1:<random port>.
     */
    @Suppress("LongParameterList")
    fun newSession(
        hop: ConnectionConfig,
        host: String,
        port: Int,
        alias: String?,
        hostKeyStore: HostKeyStore,
        allowKey: Boolean,
        userInfo: (TofuHostKeyRepository) -> UserInfo,
    ): Session {
        val jsch = JSch()
        val hostKeys = TofuHostKeyRepository(hostKeyStore)
        jsch.hostKeyRepository = hostKeys
        // Only the chosen method: a stored secret of the other one must not log in silently.
        val useKey = allowKey && hop.usesKey()
        if (useKey) {
            val passphrase = hop.privateKeyPassphrase.takeIf { it.isNotBlank() }?.toByteArray()
            jsch.addIdentity("key_${hop.id}", hop.privateKey.toByteArray(), null, passphrase)
        }
        val session = jsch.getSession(hop.username, host, port)
        alias?.let { session.setHostKeyAlias(it) }
        session.setConfig(
            Properties().apply {
                // Trust on first use: unknown or changed host keys go to the user.
                set("StrictHostKeyChecking", "ask")
                set("server_host_key", SERVER_HOST_KEYS)
                set("PreferredAuthentications", preferredAuths(useKey))
            },
        )
        if (!useKey && hop.password.isNotBlank()) session.setPassword(hop.password.toByteArray())
        session.userInfo = userInfo(hostKeys)
        return session
    }
}
