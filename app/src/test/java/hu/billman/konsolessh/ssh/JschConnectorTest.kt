package hu.billman.konsolessh.ssh

import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.AuthType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Which credentials a session offers, decided without connecting it. */
class JschConnectorTest {

    private val store = HostKeyStore(File(Files.createTempDirectory("connector").toFile(), "known_hosts"))
    private val key = SshKeyGenerator.generateEd25519("test").privatePem

    private fun session(hop: ConnectionConfig, allowKey: Boolean = true): Session =
        JschConnector.newSession(hop, "203.0.113.5", 22, null, store, allowKey) { mockk<UserInfo>(relaxed = true) }

    private fun Session.auths() = getConfig("PreferredAuthentications")

    @Test
    fun `password mode offers only the password, even with a stored key`() {
        val hop = ConnectionConfig(username = "u", authType = AuthType.PASSWORD, password = "pw", privateKey = key)
        assertEquals("password,keyboard-interactive", session(hop).auths())
    }

    @Test
    fun `key mode offers only the key, even with a stored password`() {
        val hop = ConnectionConfig(username = "u", authType = AuthType.PRIVATE_KEY, password = "pw", privateKey = key)
        assertEquals("publickey", session(hop).auths())
    }

    @Test
    fun `a key the caller does not allow falls back to the password`() {
        val hop = ConnectionConfig(username = "u", authType = AuthType.PRIVATE_KEY, password = "pw", privateKey = key)
        assertEquals("password,keyboard-interactive", session(hop, allowKey = false).auths())
    }
}
