package hu.billman.konsolessh.terminal

/**
 * Platform-independent data types of the TerminalBuffer.
 *
 * The buffer is plain JVM code with no android.graphics calls, so colours
 * are ARGB Ints rather than Android Color objects. The renderer
 * (TerminalView) hands `TermColor.argb` straight to its Paint.
 */

/**
 * An ARGB colour with the same bits as android.graphics.Color, so the
 * renderer can assign it to `paint.color` unchanged.
 */
@JvmInline
value class TermColor(val argb: Int) {
    companion object {
        val DEFAULT_FG = TermColor(0xFFCCCCCC.toInt())
        val DEFAULT_BG = TermColor(0xFF000000.toInt())

        /**
         * An opaque colour from its RGB components; the same bits as
         * android.graphics.Color.rgb(r, g, b).
         */
        fun rgb(r: Int, g: Int, b: Int): TermColor {
            val rr = r and 0xFF
            val gg = g and 0xFF
            val bb = b and 0xFF
            return TermColor((0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb)
        }
    }
}

/**
 * A grid cell. Mutable, because the buffer updates cells in place, which
 * saves a lot of allocation with a large scrollback. Outside the buffer it
 * is to be treated as read-only.
 */
data class TermCell(
    var ch: String = " ",
    var fg: TermColor = TermColor.DEFAULT_FG,
    var bg: TermColor = TermColor.DEFAULT_BG,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false,
    var dim: Boolean = false,
    var italic: Boolean = false,
    var strikethrough: Boolean = false,
) {
    companion object {
        /**
         * A new blank cell on every call; a shared constant would not do,
         * since the buffer writes cells in place.
         */
        fun blank() = TermCell()
    }
}

/** One logical line, in the scrollback or on the live screen. */
typealias TermLine = Array<TermCell>

/** The terminal size in cells. */
data class TermSize(val cols: Int, val rows: Int)

/** The cursor position, 0-based; the buffer converts CSI's 1-based addresses. */
data class CursorPos(val row: Int, val col: Int)

/**
 * An immutable snapshot for the renderer. Each frame takes a fresh one,
 * which is cheap since it holds only plain values, so the view keeps no
 * reference to the buffer's internal state.
 */
data class TerminalSnapshot(
    val size: TermSize,
    val scrollbackSize: Int,
    val cursor: CursorPos,
    val cursorHidden: Boolean,
    val altScreenActive: Boolean,
    val appCursorKeys: Boolean,
    val bracketedPasteMode: Boolean,
)
