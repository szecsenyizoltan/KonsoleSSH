package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SshSession's state handling without connecting: every call is safe before
 * connect and after disconnect. The network path is covered with a real
 * sshd by SshIntegrationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SshSessionTest {

    private fun session() = SshSession(
        ConnectionConfig(host = "localhost", username = "user", auth = ConnectionConfig.Auth.Password("pass")),
        HostKeyStore(File.createTempFile("known_hosts", null).apply { deleteOnExit() }),
    )

    @Test
    fun `new session is not connected and reads end of stream`() {
        val s = session()
        assertFalse(s.isConnected)
        assertEquals(-1, s.read(ByteArray(8)))
    }

    @Test
    fun `send and resize before connect are no-ops`() {
        val s = session()
        s.send(byteArrayOf(3))
        s.resize(80, 24)
        assertFalse(s.isConnected)
    }

    @Test
    fun `disconnect is idempotent and later sends are ignored`() {
        val s = session()
        s.disconnect()
        s.disconnect()
        s.send("ls\n".toByteArray())
        s.resize(100, 30)
        assertFalse(s.isConnected)
    }
}
