package hu.billman.konsolessh.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkAccessTest {

    @Test
    fun `JSch socket timeout counts as possible block`() {
        assertTrue(LocalNetworkAccess.looksBlocked("timeout: socket is not established"))
    }

    @Test
    fun `connect timed out counts as possible block`() {
        assertTrue(LocalNetworkAccess.looksBlocked("java.net.SocketTimeoutException: connect Timed Out"))
    }

    @Test
    fun `EPERM counts as possible block`() {
        assertTrue(LocalNetworkAccess.looksBlocked("sendto failed: EPERM (Operation not permitted)"))
    }

    @Test
    fun `authentication failure is not a block`() {
        assertFalse(LocalNetworkAccess.looksBlocked("Auth fail"))
    }

    @Test
    fun `connection refused is not a block`() {
        assertFalse(LocalNetworkAccess.looksBlocked("Connection refused"))
    }
}
