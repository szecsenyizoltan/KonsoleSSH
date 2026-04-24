package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.ui.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tesztek a SessionEvent sealed hierarchiához.
 * A hierarchia a SshForegroundService Flow-felületének kulcsa, ezért
 * az equality-kontraktus (különösen a ByteArray-re) kritikus.
 */
class SessionEventTest {

    @Test
    fun `Data equality is content-based on bytes`() {
        val a = SessionEvent.Data(byteArrayOf(1, 2, 3))
        val b = SessionEvent.Data(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `Data inequality for different byte content`() {
        val a = SessionEvent.Data(byteArrayOf(1, 2, 3))
        val b = SessionEvent.Data(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `Data inequality for different length`() {
        val a = SessionEvent.Data(byteArrayOf(1, 2, 3))
        val b = SessionEvent.Data(byteArrayOf(1, 2))
        assertNotEquals(a, b)
    }

    @Test
    fun `Data toString masks the byte payload and exposes size only`() {
        val ev = SessionEvent.Data(byteArrayOf(10, 20, 30))
        val s = ev.toString()
        assertTrue("expected size=3 in toString: $s", s.contains("size=3"))
    }

    @Test
    fun `StatusChange data class equality`() {
        val a = SessionEvent.StatusChange(ConnectionStatus.CONNECTED)
        val b = SessionEvent.StatusChange(ConnectionStatus.CONNECTED)
        val c = SessionEvent.StatusChange(ConnectionStatus.DISCONNECTED)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `ConnectError data class equality`() {
        val a = SessionEvent.ConnectError("bad credentials")
        val b = SessionEvent.ConnectError("bad credentials")
        val c = SessionEvent.ConnectError("timeout")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `sealed hierarchy supports exhaustive when without else`() {
        val events: List<SessionEvent> = listOf(
            SessionEvent.Data(byteArrayOf(0)),
            SessionEvent.StatusChange(ConnectionStatus.CONNECTING),
            SessionEvent.ConnectError("network is unreachable"),
        )
        for (ev in events) {
            // Ha később új típus kerül a hierarchiába, a Kotlin compiler
            // figyelmeztet minden ilyen exhaustive mintát — ez szándékos.
            val kind: String = when (ev) {
                is SessionEvent.Data -> "data"
                is SessionEvent.StatusChange -> "status"
                is SessionEvent.ConnectError -> "error"
            }
            assertFalse(kind.isEmpty())
        }
    }
}
