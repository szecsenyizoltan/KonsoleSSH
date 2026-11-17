package hu.billman.konsolessh.ssh

import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.model.ConnectionConfig.Auth
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
    fun `a password connection offers only the password`() {
        val hop = ConnectionConfig(username = "u", auth = Auth.Password("pw"))
        assertEquals("password,keyboard-interactive", session(hop).auths())
    }

    @Test
    fun `a key connection offers only the key`() {
        val hop = ConnectionConfig(username = "u", auth = Auth.PrivateKey(key))
        assertEquals("publickey", session(hop).auths())
    }

    @Test
    fun `a key the caller does not allow is not offered`() {
        val hop = ConnectionConfig(username = "u", auth = Auth.PrivateKey(key))
        assertEquals("password,keyboard-interactive", session(hop, allowKey = false).auths())
    }
}
