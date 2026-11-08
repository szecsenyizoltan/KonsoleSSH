package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import java.util.Properties

/**
 * Egy ugrópont (hop) JSch-sessionjének közös beállítása. A terminál és a
 * rövid életű próbakapcsolatok (teszt, kulcsfeltöltés) ugyanezt használják.
 */
internal object JschConnector {

    const val CONNECT_TIMEOUT_MS = 15_000

    // Legacy ssh-rsa (SHA-1 RSA) is kept last for old routers / embedded
    // servers; JSch 0.2.x dropped it by default.
    private const val SERVER_HOST_KEYS =
        "ssh-ed25519,ecdsa-sha2-nistp521,ecdsa-sha2-nistp384," +
            "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa"

    fun preferredAuths(hasKey: Boolean, hasPassword: Boolean): String = when {
        hasKey && hasPassword -> "publickey,password,keyboard-interactive"
        hasKey -> "publickey"
        else -> "password,keyboard-interactive"
    }

    /**
     * Létrehozza és beállítja (de nem kapcsolja) a sessiont. Jump mögött a
     * [host]/[port] a helyi továbbított port, az [alias] a valódi név, hogy a
     * host key ne 127.0.0.1:<véletlen port> alatt tárolódjon.
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
        val useKey = allowKey && hop.privateKey.isNotBlank()
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
                set("PreferredAuthentications", preferredAuths(useKey, hop.password.isNotBlank()))
            },
        )
        if (hop.password.isNotBlank()) session.setPassword(hop.password)
        session.userInfo = userInfo(hostKeys)
        return session
    }
}
