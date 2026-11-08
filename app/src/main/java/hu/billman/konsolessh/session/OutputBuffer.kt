package hu.billman.konsolessh.session

import java.io.ByteArrayOutputStream

/** Kimeneti darab a folyam [start] pozíciójától. */
class OutputChunk(val start: Long, val bytes: ByteArray) {
    val end: Long get() = start + bytes.size
}

/**
 * Egy fül utolsó [maxBytes] bájtja, a folyam abszolút pozíciójával együtt.
 * A pozíció alapján a felület a pillanatkép és az élő folyam határán sem
 * duplikál, és nem is hagy ki bájtot.
 */
class OutputBuffer(private val maxBytes: Int = DEFAULT_MAX_BYTES) {

    private val buf = ByteArrayOutputStream()
    private var end: Long = 0

    @Synchronized
    fun append(bytes: ByteArray): OutputChunk {
        val chunk = OutputChunk(end, bytes)
        buf.write(bytes)
        end += bytes.size
        if (buf.size() > maxBytes) {
            val cur = buf.toByteArray()
            buf.reset()
            buf.write(cur, cur.size - maxBytes, maxBytes)
        }
        return chunk
    }

    /** A tárolt bájtok és a folyam pozíciója az utolsó bájt után. */
    @Synchronized
    fun snapshot(): OutputChunk {
        val bytes = buf.toByteArray()
        return OutputChunk(end - bytes.size, bytes)
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 256 * 1024
    }
}
