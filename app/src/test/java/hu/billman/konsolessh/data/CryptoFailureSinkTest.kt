package hu.billman.konsolessh.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CryptoFailureSinkTest {

    @After
    fun reset() {
        // Állítsuk vissza a default handler-t a teszt végén.
        CryptoFailureSink.handler = { tag, message, cause ->
            android.util.Log.wtf(tag, message, cause)
        }
    }

    @Test
    fun `report invokes the currently installed handler`() {
        var capturedTag: String? = null
        var capturedMessage: String? = null
        var capturedCause: Throwable? = null
        CryptoFailureSink.handler = { tag, message, cause ->
            capturedTag = tag
            capturedMessage = message
            capturedCause = cause
        }
        val ex = RuntimeException("keystore broke")
        CryptoFailureSink.report("TAG", "bad thing", ex)

        assertEquals("TAG", capturedTag)
        assertEquals("bad thing", capturedMessage)
        assertSame(ex, capturedCause)
    }

    @Test
    fun `handler can be swapped at runtime`() {
        val calls = mutableListOf<String>()
        CryptoFailureSink.handler = { _, msg, _ -> calls += "first:$msg" }
        CryptoFailureSink.report("T", "one", RuntimeException())
        CryptoFailureSink.handler = { _, msg, _ -> calls += "second:$msg" }
        CryptoFailureSink.report("T", "two", RuntimeException())

        assertEquals(listOf("first:one", "second:two"), calls)
    }
}
