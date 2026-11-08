package hu.billman.konsolessh.ssh

import com.jcraft.jsch.JSchException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SshErrorClassifierTest {

    private fun classify(message: String?, cause: Throwable? = null) =
        SshErrorClassifier.classify(JSchException(message, cause))

    @Test
    fun `host key outcomes`() {
        assertEquals(SshError.HostKeyChanged, classify("HostKey has been changed: [10.0.2.2]:22"))
        assertEquals(SshError.HostKeyRejected, classify("reject HostKey: 10.0.2.2"))
    }

    @Test
    fun `network failures, cause chain included`() {
        assertEquals(SshError.ConnectionRefused, classify("java.net.ConnectException: Connection refused"))
        assertEquals(SshError.Timeout, classify("session.connect", SocketTimeoutException("connect timed out")))
        assertEquals(SshError.Timeout, classify("timeout: socket is not established"))
        assertEquals(SshError.NotPermitted, classify("sendto failed: EPERM (Operation not permitted)"))
        assertEquals(SshError.NoRoute, classify("No route to host"))
        assertEquals(SshError.ConnectionClosed, classify("Session.connect: java.net.SocketException: Connection reset"))
        assertEquals(SshError.NetworkUnreachable, classify("Network is unreachable"))
        assertEquals(SshError.UnknownHost, classify("java.net.UnknownHostException: Unable to resolve host"))
        assertEquals(SshError.UnknownHost, classify("java.net.UnknownHostException: nosuch.example"))
    }

    @Test
    fun `authentication failures, specific before generic`() {
        assertEquals(SshError.BadCredentials, classify("bad credentials: Auth cancel"))
        assertEquals(SshError.AuthCancelled, classify("Auth cancel for methods 'publickey'"))
        assertEquals(SshError.UserAuthFailed, classify("USERAUTH fail"))
        assertEquals(SshError.AuthFailed, classify("Auth fail for methods 'publickey'"))
    }

    @Test
    fun `jump chain problems`() {
        assertEquals(
            SshError.JumpNotFound,
            SshErrorClassifier.classify(JumpChainException(JumpChainException.Reason.NOT_FOUND)),
        )
        assertEquals(
            SshError.JumpChainInvalid,
            SshErrorClassifier.classify(JumpChainException(JumpChainException.Reason.CYCLE)),
        )
    }

    @Test
    fun `unknown message keeps a cleaned detail`() {
        assertEquals(
            SshError.Other("weird thing"),
            SshErrorClassifier.classify(IOException("com.example.Foo: weird thing")),
        )
        assertEquals(SshError.Other("IOException"), SshErrorClassifier.classify(IOException()))
    }

    @Test
    fun `detail strips prefixes and truncates`() {
        assertEquals("boom", SshErrorClassifier.detail("session.connect: boom"))
        assertEquals("refused", SshErrorClassifier.detail("java.net.ConnectException: refused"))
        assertEquals("plain message", SshErrorClassifier.detail("plain message"))
        assertEquals(200, SshErrorClassifier.detail("a".repeat(500)).length)
        assertEquals("odd", SshErrorClassifier.detail("Session.connect: java.net.SocketException: odd"))
    }
}
