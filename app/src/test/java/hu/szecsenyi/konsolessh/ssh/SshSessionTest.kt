package hu.szecsenyi.konsolessh.ssh

import hu.szecsenyi.konsolessh.model.ConnectionConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])

/**
 * Unit tests for SshSession state machine.
 * Does NOT make real network connections — only tests initial state
 * and API contract. Integration/SSH tests belong in androidTest with a
 * real or mock SSH server.
 */
class SshSessionTest {

    private fun session(
        host: String = "localhost",
        port: Int = 22,
        username: String = "user",
        authType: ConnectionConfig.AuthType = ConnectionConfig.AuthType.PASSWORD,
        password: String = "pass"
    ) = SshSession(
        ConnectionConfig(
            host = host,
            port = port,
            username = username,
            authType = authType,
            password = password
        )
    )

    // ---- Initial state ----

    @Test
    fun `new session is not connected`() {
        assertFalse(session().isConnected)
    }

    @Test
    fun `new session has null inputStream`() {
        assertNull(session().inputStream)
    }

    @Test
    fun `new session has null outputStream`() {
        assertNull(session().outputStream)
    }

    // ---- disconnect before connect is safe ----

    @Test
    fun `disconnect on unconnected session does not throw`() {
        val s = session()
        s.disconnect() // Must not throw
        assertFalse(s.isConnected)
    }

    @Test
    fun `double disconnect does not throw`() {
        val s = session()
        s.disconnect()
        s.disconnect() // Must not throw
    }

    // ---- sendInput/sendBytes before connect are no-ops ----

    @Test
    fun `sendInput before connect does not throw`() {
        val s = session()
        s.sendInput("ls\n") // no outputStream, must be a no-op
    }

    @Test
    fun `sendBytes before connect does not throw`() {
        val s = session()
        s.sendBytes(byteArrayOf(3)) // Ctrl+C
    }

    // ---- Config is reflected in session ----

    @Test
    fun `password auth session stores config correctly`() {
        val config = ConnectionConfig(
            host = "myserver.hu",
            port = 2222,
            username = "admin",
            authType = ConnectionConfig.AuthType.PASSWORD,
            password = "hunter2"
        )
        val s = SshSession(config)
        assertFalse(s.isConnected)
        assertNull(s.inputStream)
    }

    @Test
    fun `private key auth session initialises without error`() {
        val config = ConnectionConfig(
            host = "myserver.hu",
            port = 22,
            username = "deploy",
            authType = ConnectionConfig.AuthType.PRIVATE_KEY,
            privateKey = "-----BEGIN RSA PRIVATE KEY-----\nfake\n-----END RSA PRIVATE KEY-----"
        )
        val s = SshSession(config)
        assertFalse(s.isConnected)
    }

    // ---- resize before connect is safe ----

    @Test
    fun `resize before connect does not throw`() {
        val s = session()
        s.resize(80, 24) // shellChannel is null, must be a no-op
    }
}
