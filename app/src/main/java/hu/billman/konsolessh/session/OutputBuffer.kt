package hu.billman.konsolessh.session

/** A piece of output starting at stream position [start]. */
class OutputChunk(val start: Long, val bytes: ByteArray) {
    val end: Long get() = start + bytes.size
}

/**
 * A tab's last [maxBytes] bytes, with their absolute stream position. The
 * position lets the UI join the snapshot and the live flow without
 * drawing a byte twice or skipping one.
 *
 * A ring buffer: even when full, a write copies nothing extra; the bytes
 * are copied once, when a snapshot is taken.
 */
class OutputBuffer(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val ring = ByteArray(maxBytes)

    /** Where the next write goes in [ring]. */
    private var head = 0
    private var size = 0
    private var end: Long = 0

    @Synchronized
    fun append(bytes: ByteArray): OutputChunk {
        val chunk = OutputChunk(end, bytes)
        end += bytes.size
        // Only the last maxBytes of an oversized chunk can survive anyway.
        val from = maxOf(0, bytes.size - maxBytes)
        var remaining = bytes.size - from
        var src = from
        while (remaining > 0) {
            val n = minOf(remaining, maxBytes - head)
            bytes.copyInto(ring, head, src, src + n)
            head = (head + n) % maxBytes
            src += n
            remaining -= n
        }
        size = minOf(maxBytes, size + bytes.size)
        return chunk
    }

    /** The stored bytes, and the stream position after the last of them. */
    @Synchronized
    fun snapshot(): OutputChunk {
        val out = ByteArray(size)
        val start = (head - size + maxBytes) % maxBytes
        val firstPart = minOf(size, maxBytes - start)
        ring.copyInto(out, 0, start, start + firstPart)
        ring.copyInto(out, firstPart, 0, size - firstPart)
        return OutputChunk(end - size, out)
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 256 * 1024
    }
}
