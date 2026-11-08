package hu.billman.konsolessh.session

/** Kimeneti darab a folyam [start] pozíciójától. */
class OutputChunk(val start: Long, val bytes: ByteArray) {
    val end: Long get() = start + bytes.size
}

/**
 * Egy fül utolsó [maxBytes] bájtja, a folyam abszolút pozíciójával együtt.
 * A pozíció alapján a felület a pillanatkép és az élő folyam határán sem
 * duplikál, és nem is hagy ki bájtot.
 *
 * Körpuffer: tele állapotban sem másol írásonként, csak a pillanatkép
 * készítésekor egyszer.
 */
class OutputBuffer(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val ring = ByteArray(maxBytes)

    /** A következő írás helye a [ring]-ben. */
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

    /** A tárolt bájtok és a folyam pozíciója az utolsó bájt után. */
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
