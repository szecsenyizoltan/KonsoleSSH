package hu.billman.konsolessh.terminal

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws the terminal's cells on a Canvas: colours, attributes, the cursor
 * and the selection highlight. It owns the paints and the cell metrics;
 * the view decides what is visible (scroll position, cursor blink) and
 * passes it to [draw]. Called on the UI thread only.
 */
internal class TerminalRenderer(typeface: Typeface) {

    var cellWidth = 1f
        private set
    var cellHeight = 1f
        private set
    private var cellBase = 0f

    /** The text size in pixels, as last set. */
    val textSize: Float get() = normPaint.textSize

    private val bgPaint = Paint()
    private val normPaint = Paint().apply {
        this.typeface = typeface
        isAntiAlias = true
    }
    private val boldPaint = Paint().apply {
        this.typeface = Typeface.create(typeface, Typeface.BOLD)
        isAntiAlias = true
    }
    private var runChars = CharArray(INITIAL_RUN)
    private val normAdvance = ByteArray(FIXED_ADVANCE_LIMIT)
    private val boldAdvance = ByteArray(FIXED_ADVANCE_LIMIT)
    private val cellRect = RectF()

    /** Sets the text size and derives the cell size from the font. */
    fun setTextSize(px: Float) {
        normPaint.textSize = px
        boldPaint.textSize = px
        val fm = normPaint.fontMetrics
        cellHeight = fm.descent - fm.ascent
        cellBase = -fm.ascent
        cellWidth = normPaint.measureText("M")
        normAdvance.fill(0)
        boldAdvance.fill(0)
    }

    /** What stays fixed during one draw; the selection bounds are computed once. */
    private class Frame(
        val scrollbackSize: Int,
        val cursorRow: Int,
        val cursorCol: Int,
        val showCursor: Boolean,
        val selection: IntArray?,
        val firstCol: Int,
        val lastCol: Int,
    )

    /**
     * Draws the visible rows: [firstLine] is the logical line on the top
     * row, [firstCol] the first column shown, [selection] the bounds from
     * [TerminalSelection.bounds]. The caller holds the buffer's lock.
     */
    fun draw(
        canvas: Canvas,
        buffer: TerminalBuffer,
        firstLine: Int,
        firstCol: Int,
        showCursor: Boolean,
        selection: IntArray?,
    ) {
        val frame = Frame(
            scrollbackSize = buffer.scrollbackSize,
            cursorRow = buffer.cursorRow,
            cursorCol = buffer.cursorCol,
            showCursor = showCursor,
            selection = selection,
            firstCol = firstCol,
            lastCol = firstCol + buffer.cols + 1,
        )
        for (r in 0 until buffer.rows) {
            val lineIdx = firstLine + r
            val row = if (lineIdx < 0) null else buffer.lineAt(lineIdx)
            if (row != null) drawRow(canvas, row, lineIdx, r * cellHeight, frame)
        }
    }

    // Adjacent cells with the same look form a run: one background rectangle
    // and, for fixed-advance glyphs, one drawText call instead of one per cell.
    private fun drawRow(canvas: Canvas, row: TermLine, lineIdx: Int, y: Float, frame: Frame) {
        val cursorCol = if (frame.showCursor && lineIdx - frame.scrollbackSize == frame.cursorRow) frame.cursorCol else -1
        val cTo = min(row.size, frame.lastCol)
        var c = frame.firstCol
        while (c < cTo) {
            val look = lookAt(row, lineIdx, c, cursorCol, frame.selection)
            var end = c + 1
            while (end < cTo && lookAt(row, lineIdx, end, cursorCol, frame.selection) == look) end++
            drawRun(canvas, row, c, end, y, look, frame.firstCol)
            c = end
        }
    }

    /**
     * A cell's look packed into one Long: foreground (RGB, always opaque),
     * background (ARGB, translucent for the selection), bold, underline.
     */
    private fun lookAt(row: TermLine, lineIdx: Int, c: Int, cursorCol: Int, selection: IntArray?): Long {
        val cell = row[c]
        val bg = if (cell.reverse) cell.fg.argb else cell.bg.argb
        val fg = (if (cell.reverse) cell.bg.argb else cell.fg.argb).let { if (cell.dim) halfway(it, bg) else it }
        val isCursor = c == cursorCol
        val effectiveBg = when {
            selection != null && TerminalSelection.contains(selection, lineIdx, c) -> SELECTION_BG
            isCursor -> fg
            else -> bg
        }
        val effectiveFg = if (isCursor) bg else fg
        val flags = (if (cell.bold) LOOK_BOLD else 0L) or
            (if (cell.underline) LOOK_UNDERLINE else 0L) or
            (if (cell.italic) LOOK_ITALIC else 0L) or
            (if (cell.strikethrough) LOOK_STRIKE else 0L)
        return ((effectiveFg.toLong() and LOW_24) shl FG_SHIFT) or
            ((effectiveBg.toLong() and LOW_32) shl BG_SHIFT) or flags
    }

    private fun drawRun(canvas: Canvas, row: TermLine, from: Int, to: Int, y: Float, look: Long, firstCol: Int) {
        val x = (from - firstCol) * cellWidth
        val width = (to - from) * cellWidth
        val bg = ((look ushr BG_SHIFT) and LOW_32).toInt()
        if (bg != BACKGROUND) {
            bgPaint.color = bg
            cellRect.set(x, y, x + width, y + cellHeight)
            canvas.drawRect(cellRect, bgPaint)
        }
        val paint = if (look and LOOK_BOLD != 0L) boldPaint else normPaint
        paint.color = OPAQUE or ((look ushr FG_SHIFT) and LOW_24).toInt()
        paint.textSkewX = if (look and LOOK_ITALIC != 0L) ITALIC_SKEW else 0f
        if (!drawFixedAdvanceRun(canvas, row, from, to, x, y, paint)) {
            for (c in from until to) {
                val ch = row[c].ch
                if (ch != " " && ch != TerminalBuffer.WIDE_TAIL) {
                    canvas.drawText(ch, (c - firstCol) * cellWidth, y + cellBase, paint)
                }
            }
        }
        if (look and LOOK_UNDERLINE != 0L) {
            canvas.drawLine(x, y + cellHeight - 1f, x + width, y + cellHeight - 1f, paint)
        }
        if (look and LOOK_STRIKE != 0L) {
            val mid = y + cellHeight / 2f
            canvas.drawLine(x, mid, x + width, mid, paint)
        }
        paint.textSkewX = 0f
    }

    /** Draws the run in one call if each of its characters is exactly one cell wide. */
    private fun drawFixedAdvanceRun(
        canvas: Canvas,
        row: TermLine,
        from: Int,
        to: Int,
        x: Float,
        y: Float,
        paint: Paint,
    ): Boolean {
        val n = to - from
        if (runChars.size < n) runChars = CharArray(n)
        var blank = true
        for (i in 0 until n) {
            val ch = row[from + i].ch
            if (ch.length != 1 || !hasCellAdvance(ch[0], paint)) return false
            runChars[i] = ch[0]
            if (ch[0] != ' ') blank = false
        }
        if (!blank) canvas.drawText(runChars, 0, n, x, y + cellBase, paint)
        return true
    }

    // Cached per paint: 0 = not measured yet, 1 = exactly one cell wide, 2 = not.
    private fun hasCellAdvance(ch: Char, paint: Paint): Boolean {
        if (ch.code >= FIXED_ADVANCE_LIMIT) return false
        val cache = if (paint === boldPaint) boldAdvance else normAdvance
        if (cache[ch.code] == 0.toByte()) {
            cache[ch.code] = if (abs(paint.measureText(ch.toString()) - cellWidth) < ADVANCE_TOLERANCE) 1 else 2
        }
        return cache[ch.code] == 1.toByte()
    }

    companion object {
        val BACKGROUND = Color.BLACK
        private val SELECTION_BG = Color.argb(160, 70, 130, 220)
        private const val INITIAL_RUN = 80
        private const val OPAQUE = 0xFF shl 24
        private const val LOW_24 = 0xFFFFFFL
        private const val LOW_32 = 0xFFFFFFFFL
        private const val BG_SHIFT = 4
        private const val FG_SHIFT = 36
        private const val LOOK_BOLD = 1L
        private const val LOOK_UNDERLINE = 2L
        private const val LOOK_ITALIC = 4L
        private const val LOOK_STRIKE = 8L

        /** Synthetic slant: the bundled Nerd Font has no italic variant. */
        private const val ITALIC_SKEW = -0.2f

        /** The faint (SGR 2) colour: halfway between foreground and background. */
        private fun halfway(fg: Int, bg: Int): Int {
            fun mix(shift: Int) = (((fg ushr shift) and 0xFF) + ((bg ushr shift) and 0xFF)) / 2
            return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        /** Up to this code point (Latin-1, Latin Extended-A/B), text is drawn in runs where possible. */
        private const val FIXED_ADVANCE_LIMIT = 0x250
        private const val ADVANCE_TOLERANCE = 0.05f
    }
}
