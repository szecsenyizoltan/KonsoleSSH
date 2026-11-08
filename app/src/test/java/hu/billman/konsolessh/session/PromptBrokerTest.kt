package hu.billman.konsolessh.session

import hu.billman.konsolessh.ssh.HostKeyPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PromptBrokerTest {

    private val hostKey = HostKeyPrompt("srv", "ssh-ed25519", "SHA256:x", changed = false)

    private fun awaitPending(broker: PromptBroker): PendingPrompt {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (broker.pending.value == null) {
            check(System.currentTimeMillis() < deadline) { "no pending prompt" }
            Thread.sleep(POLL_MS)
        }
        return broker.pending.value!!
    }

    @Test
    fun `password answer is returned and the pending state is cleared`() {
        val broker = PromptBroker()
        val result = CompletableFuture.supplyAsync { broker.password("u@srv") }
        val prompt = awaitPending(broker) as PendingPrompt.Password
        assertEquals("u@srv", prompt.displayHost)

        broker.answer(prompt.id, PromptAnswer.Password("s3cret"))

        assertEquals("s3cret", result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        assertNull(broker.pending.value)
    }

    @Test
    fun `answer with a stale id or of the wrong kind is ignored`() {
        val broker = PromptBroker()
        val result = CompletableFuture.supplyAsync { broker.confirmHostKey(hostKey) }
        val prompt = awaitPending(broker)

        broker.answer(prompt.id + 1000, PromptAnswer.HostKey(true))
        broker.answer(prompt.id, PromptAnswer.Password("wrong kind"))
        broker.answer(prompt.id, PromptAnswer.HostKey(false))

        assertFalse(result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `no answer within the timeout rejects`() {
        val broker = PromptBroker(timeoutMs = 50)
        assertFalse(broker.confirmHostKey(hostKey))
        assertNull(broker.password("u@srv"))
        assertNull(broker.pending.value)
    }

    @Test
    fun `cancel releases a waiting question`() {
        val broker = PromptBroker()
        val result = CompletableFuture.supplyAsync { broker.confirmHostKey(hostKey) }
        awaitPending(broker)
        broker.cancel()
        assertFalse(result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L
    }
}
