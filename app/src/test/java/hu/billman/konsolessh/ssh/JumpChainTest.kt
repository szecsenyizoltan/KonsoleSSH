package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM teszt: a jump-lánc feloldása a mentett kapcsolatokból. */
class JumpChainTest {

    private fun cfg(id: String, host: String, jump: String = "") =
        ConnectionConfig(id = id, host = host, username = "u", jumpConnectionId = jump)

    private fun reason(block: () -> Unit): JumpChainException.Reason =
        try {
            block()
            error("expected JumpChainException")
        } catch (e: JumpChainException) {
            e.reason
        }

    @Test
    fun `no jump gives an empty chain`() {
        assertTrue(JumpChain.resolve(cfg("t", "10.0.0.5"), emptyList()).isEmpty())
    }

    @Test
    fun `single saved jump`() {
        val gw = cfg("gw", "gw.example.com")
        val chain = JumpChain.resolve(cfg("t", "10.0.0.5", jump = "gw"), listOf(gw))
        assertEquals(listOf("gw.example.com"), chain.map { it.host })
    }

    @Test
    fun `internal hypervisor behind a public gateway is reached in two hops, outermost first`() {
        val gw = cfg("gw", "gw.example.com")
        val hv = cfg("hv", "192.168.1.3", jump = "gw")
        val vm = cfg("vm", "192.168.122.10", jump = "hv")

        val chain = JumpChain.resolve(vm, listOf(vm, hv, gw))

        assertEquals(listOf("gw.example.com", "192.168.1.3"), chain.map { it.host })
    }

    @Test
    fun `missing jump reference is NOT_FOUND`() {
        assertEquals(
            JumpChainException.Reason.NOT_FOUND,
            reason { JumpChain.resolve(cfg("t", "h", jump = "gone"), emptyList()) },
        )
    }

    @Test
    fun `jump referring back to the target is a CYCLE`() {
        val a = cfg("a", "a", jump = "b")
        val b = cfg("b", "b", jump = "a")
        assertEquals(JumpChainException.Reason.CYCLE, reason { JumpChain.resolve(a, listOf(a, b)) })
    }

    @Test
    fun `self reference is a CYCLE`() {
        val a = cfg("a", "a", jump = "a")
        assertEquals(JumpChainException.Reason.CYCLE, reason { JumpChain.resolve(a, listOf(a)) })
    }

    @Test
    fun `chain longer than the limit is TOO_LONG`() {
        val n = JumpChain.MAX_HOPS + 1
        val saved = (1..n).map { i -> cfg("h$i", "h$i", jump = if (i < n) "h${i + 1}" else "") }
        val target = cfg("t", "t", jump = "h1")
        assertEquals(JumpChainException.Reason.TOO_LONG, reason { JumpChain.resolve(target, saved) })
    }

    @Test
    fun `chain at the limit is allowed`() {
        val n = JumpChain.MAX_HOPS
        val saved = (1..n).map { i -> cfg("h$i", "h$i", jump = if (i < n) "h${i + 1}" else "") }
        val chain = JumpChain.resolve(cfg("t", "t", jump = "h1"), saved)
        assertEquals((n downTo 1).map { "h$it" }, chain.map { it.host })
    }

    @Test
    fun `legacy manual jump fields become a single hop`() {
        val target = ConnectionConfig(
            host = "10.0.0.5",
            jumpHost = "gw.example.com",
            jumpPort = 2222,
            jumpUsername = "jumper",
            jumpPassword = "pw",
        )
        val hop = JumpChain.resolve(target, emptyList()).single()
        assertEquals("gw.example.com", hop.host)
        assertEquals(2222, hop.port)
        assertEquals("jumper", hop.username)
    }
}
