package hu.billman.konsolessh.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputBufferTest {

    @Test
    fun `chunks carry consecutive absolute positions`() {
        val buffer = OutputBuffer()
        val a = buffer.append("abc".toByteArray())
        val b = buffer.append("de".toByteArray())
        assertEquals(0L, a.start)
        assertEquals(3L, b.start)
        assertEquals(5L, b.end)
    }

    @Test
    fun `snapshot keeps the tail and its real start position after truncation`() {
        val buffer = OutputBuffer(maxBytes = 4)
        buffer.append("abcdef".toByteArray())
        buffer.append("gh".toByteArray())

        val snap = buffer.snapshot()

        assertEquals("efgh", String(snap.bytes))
        assertEquals(4L, snap.start)
        assertEquals(8L, snap.end)
    }

    @Test
    fun `empty buffer snapshot is empty at position zero`() {
        val snap = OutputBuffer().snapshot()
        assertEquals(0, snap.bytes.size)
        assertEquals(0L, snap.start)
    }

    @Test
    fun `ring buffer wraps around in many small writes`() {
        val buffer = OutputBuffer(maxBytes = 7)
        val all = StringBuilder()
        repeat(50) { i ->
            val piece = "x$i,"
            all.append(piece)
            buffer.append(piece.toByteArray())
            val snap = buffer.snapshot()
            assertEquals(all.takeLast(7).toString(), String(snap.bytes))
            assertEquals(all.length.toLong(), snap.end)
        }
    }

    @Test
    fun `chunk larger than the buffer keeps only its tail`() {
        val buffer = OutputBuffer(maxBytes = 3)
        buffer.append("ab".toByteArray())
        buffer.append("cdefg".toByteArray())
        assertEquals("efg", String(buffer.snapshot().bytes))
        assertEquals(4L, buffer.snapshot().start)
    }
}
