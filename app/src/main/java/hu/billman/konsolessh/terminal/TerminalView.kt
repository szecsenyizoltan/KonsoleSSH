package hu.billman.konsolessh.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas-based terminal emulator widget.
 *
 * Supports: SGR colors (16 + 256 + RGB), bold, underline, reverse video,
 * DECCKM, alternate screen buffer, scroll regions, insert/delete lines/chars,
 * reverse index, cursor save/restore, blinking cursor, tab stops, text
 * selection (long-press drag) with copy/paste ActionMode.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onKeyInput: ((ByteArray) -> Unit)? = null
    var onTerminalResize: ((cols: Int, rows: Int) -> Unit)? = null
    var horizontalScrollEnabled = true
    val fontSize: Float get() = fontSizeSp

    val currentCols get() = termCols
    val currentRows get() = termRows

    companion object {
        private val DEFAULT_FG = Color.rgb(204, 204, 204)
        private val DEFAULT_BG = Color.BLACK
        private val SELECTION_BG = Color.argb(160, 70, 130, 220)
        private const val MAX_SCROLLBACK = 3000
        private const val MIN_COLS = 80
        private const val LONG_PRESS_MS = 400L
        private const val CURSOR_ON_MS  = 600L
        private const val CURSOR_OFF_MS = 300L
        private const val MIN_FONT_SP = 6f
        private const val MAX_FONT_SP = 40f
        private const val ID_COPY  = 1
        private const val ID_PASTE = 2
    }

    // ── Cell ──────────────────────────────────────────────────────────────────

    private data class Cell(
        var ch: String = " ",
        var fg: Int = DEFAULT_FG,
        var bg: Int = DEFAULT_BG,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var reverse: Boolean = false
    )

    // ── Terminal dimensions ───────────────────────────────────────────────────

    private var termCols = MIN_COLS
    private var termRows = 24
    private var viewW = 0
    private var viewH = 0

    // ── Screen buffers ────────────────────────────────────────────────────────

    private var screen = newScreen()
    private var savedMainScreen: Array<Array<Cell>>? = null
    private var altScreenActive = false
    private val scrollback = ArrayDeque<Array<Cell>>()

    /**
     * Fázis 5 / Lépés 5: párhuzamos TerminalBuffer-mirror. A View saját
     * belső [screen] és [scrollback]-je ebben a commitban még a kanonikus
     * állapot — a Buffer mellékfolyásként kap minden write/clear/resize
     * hívást, hogy Lépés 6-ban a renderer átléphessen rá anélkül, hogy
     * az átmenet pillanatában üres lenne.
     */
    internal val buffer = TerminalBuffer(initialCols = MIN_COLS, initialRows = 24)

    // ── Cursor ────────────────────────────────────────────────────────────────

    private var curRow = 0; private var curCol = 0
    private var savedMainRow = 0; private var savedMainCol = 0
    private var savedRow = 0;    private var savedCol = 0

    // ── Scroll region ─────────────────────────────────────────────────────────

    private var scrollTop = 0
    private var scrollBot = termRows - 1

    // ── Terminal modes ────────────────────────────────────────────────────────

    private var appCursorKeys     = false
    private var cursorHidden      = false
    private var bracketedPasteMode = false

    // ── Current style ─────────────────────────────────────────────────────────

    private var fgColor     = DEFAULT_FG
    private var bgColor     = DEFAULT_BG
    private var isBold      = false
    private var isUnderline = false
    private var isReverse   = false

    // ── ANSI parser ───────────────────────────────────────────────────────────

    private enum class AnsiState { NORMAL, ESCAPE, CSI, OSC, DCS, CHARSET }
    private var ansiState = AnsiState.NORMAL
    private val csiParams = StringBuilder()

    // ── Font & painting ───────────────────────────────────────────────────────

    private var fontSizeSp = 12f
    private var firstLayout = true
    private val bgPaint   = Paint()
    private val normPaint = Paint().apply {
        typeface = loadTypeface(context)
        isAntiAlias = true
    }
    private val boldPaint = Paint().apply {
        typeface = Typeface.create(loadTypeface(context), Typeface.BOLD)
        isAntiAlias = true
    }
    private var cellW = 1f; private var cellH = 1f; private var cellBase = 0f
    private val cellRect = RectF()

    // ── View scroll ───────────────────────────────────────────────────────────

    private var scrollRowOff = 0
    private var scrollColOff = 0
    private var touchScrollFrac = 0f

    // ── Text selection ────────────────────────────────────────────────────────

    private var selActive    = false
    private var selStartLine = 0; private var selStartCol = 0
    private var selEndLine   = 0; private var selEndCol   = 0
    private var selActionMode: ActionMode? = null

    private var lpX = 0f; private var lpY = 0f
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { startSelectionAt(lpX, lpY) }

    // ── Cursor blink ──────────────────────────────────────────────────────────

    private var cursorOn = true
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkTask = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            blinkHandler.postDelayed(this, if (cursorOn) CURSOR_ON_MS else CURSOR_OFF_MS)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    private var touchX0 = 0f; private var touchY0 = 0f
    private var touchX  = 0f; private var touchY  = 0f
    private var touchMoved = false

    init {
        setBackgroundColor(DEFAULT_BG)
        isFocusable = true
        isFocusableInTouchMode = true
        applyFontMetrics()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun newScreen() = Array(termRows) { Array(termCols) { Cell() } }
    private fun blankRow()  = Array(termCols) { Cell() }

    private fun loadTypeface(ctx: Context): Typeface {
        return try {
            Typeface.createFromAsset(ctx.assets, "fonts/NerdFont.ttf")
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    private fun applyFontMetrics() {
        val px = fontSizeSp * resources.displayMetrics.scaledDensity
        normPaint.textSize = px
        boldPaint.textSize = px
        val fm = normPaint.fontMetrics
        cellH    = fm.descent - fm.ascent
        cellBase = -fm.ascent
        cellW    = normPaint.measureText("M")
    }

    /** First logical line index that maps to visual row 0. */
    private fun computeFirstLine(): Int =
        if (scrollback.isEmpty() && scrollRowOff == 0 && !altScreenActive)
            curRow - (termRows - 1)          // bottom-gravity: content pinned to bottom
        else
            scrollback.size + termRows - termRows - scrollRowOff

    // ── Size ──────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        viewW = w; viewH = h
        if (firstLayout) {
            firstLayout = false
            val wantedCellW = w.toFloat() / MIN_COLS
            val ratio = cellW / normPaint.textSize
            fontSizeSp = (wantedCellW / ratio / resources.displayMetrics.scaledDensity)
                .coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            applyFontMetrics()
        }
        resizeTerm(
            max(MIN_COLS, (w / cellW).toInt()),
            max(1, (h / cellH).toInt())
        )
    }

    private fun resizeTerm(nc: Int, nr: Int) {
        if (nc == termCols && nr == termRows) return
        val old = screen; val or = termRows; val oc = termCols
        termCols = nc; termRows = nr
        screen = Array(termRows) { r ->
            Array(termCols) { c ->
                if (r < or && c < oc) old[r][c].copy() else Cell()
            }
        }
        savedMainScreen = null
        scrollTop = 0; scrollBot = termRows - 1
        curRow = curRow.coerceIn(0, termRows - 1)
        curCol = curCol.coerceIn(0, termCols - 1)
        buffer.resize(nc, nr)  // Fázis 5 mirror
        onTerminalResize?.invoke(termCols, termRows)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(DEFAULT_BG)

        val firstLine = computeFirstLine()

        for (r in 0 until termRows) {
            val lineIdx = firstLine + r
            if (lineIdx < 0) continue

            val screenRow: Int
            val row: Array<Cell> = if (lineIdx < scrollback.size) {
                screenRow = -1
                scrollback[lineIdx]
            } else {
                val sr = lineIdx - scrollback.size
                if (sr >= termRows) continue
                screenRow = sr
                screen[sr]
            }

            val y = r * cellH
            val cFrom = scrollColOff
            val cTo = min(row.size, scrollColOff + termCols + 1)

            for (c in cFrom until cTo) {
                val cell = row[c]
                val drawX = (c - scrollColOff) * cellW

                val isCursor   = !cursorHidden && scrollRowOff == 0 &&
                                  screenRow == curRow && c == curCol
                val isSelected = isInSelection(lineIdx, c)

                // Resolve reverse video, then cursor inversion, then selection
                val cellFg = if (cell.reverse) cell.bg else cell.fg
                val cellBg = if (cell.reverse) cell.fg else cell.bg

                val effectiveBg = when {
                    isSelected       -> SELECTION_BG
                    isCursor && cursorOn -> cellFg
                    else             -> cellBg
                }
                val effectiveFg = when {
                    isCursor && cursorOn -> cellBg
                    else             -> cellFg
                }

                // Background
                if (effectiveBg != DEFAULT_BG || isSelected) {
                    bgPaint.color = effectiveBg
                    cellRect.set(drawX, y, drawX + cellW, y + cellH)
                    canvas.drawRect(cellRect, bgPaint)
                }

                // Character
                if (cell.ch != " ") {
                    val p = if (cell.bold) boldPaint else normPaint
                    p.color = effectiveFg
                    canvas.drawText(cell.ch, drawX, y + cellBase, p)
                }

                // Underline
                if (cell.underline) {
                    val p = if (cell.bold) boldPaint else normPaint
                    p.color = effectiveFg
                    canvas.drawLine(drawX, y + cellH - 1f, drawX + cellW, y + cellH - 1f, p)
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun append(bytes: ByteArray, length: Int) {
        processBytes(bytes, length)
        buffer.write(bytes, length)  // Fázis 5 mirror — a render még a View-screenből jön
        scrollRowOff = 0
        invalidate()
    }

    fun clear() {
        screen = newScreen(); curRow = 0; curCol = 0
        scrollTop = 0; scrollBot = termRows - 1
        buffer.clearScreen()
        invalidate()
    }

    fun scrollToBottom() { scrollRowOff = 0; invalidate() }
    fun scrollToTop()    { scrollRowOff = scrollback.size; invalidate() }

    fun setFontSize(sp: Float) {
        fontSizeSp = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        applyFontMetrics()
        if (viewW > 0 && viewH > 0) {
            resizeTerm(
                max(MIN_COLS, (viewW / cellW).toInt()),
                max(1, (viewH / cellH).toInt())
            )
        }
    }

    fun zoom(delta: Float) {
        fontSizeSp = (fontSizeSp + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        applyFontMetrics()
        if (viewW > 0 && viewH > 0) {
            resizeTerm(
                max(MIN_COLS, (viewW / cellW).toInt()),
                max(1, (viewH / cellH).toInt())
            )
        }
        invalidate()
    }

    /** Request focus only — does NOT open the soft keyboard. */
    fun focusInput() {
        post { requestFocus() }
    }

    /** Request focus AND open the soft keyboard (explicit user intent). */
    fun focusAndShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService<InputMethodManager>() ?: return
        if (!imm.showSoftInput(this, 0)) {
            post { imm.showSoftInput(this, 0) }
        }
    }

    fun cursorKeyBytes(dir: Char): ByteArray = if (appCursorKeys)
        byteArrayOf(27, 'O'.code.toByte(), dir.code.toByte())
    else
        byteArrayOf(27, '['.code.toByte(), dir.code.toByte())

    // ── ANSI parser ───────────────────────────────────────────────────────────

    private fun processBytes(bytes: ByteArray, length: Int) {
        val text = String(bytes, 0, length, Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // Supplementary character (emoji, symbols above U+FFFF): store as surrogate pair string
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                if (ansiState == AnsiState.NORMAL) printChar(text.substring(i, i + 2))
                i += 2
                continue
            }
            i++
            when (ansiState) {
                AnsiState.NORMAL -> when (ch) {
                    '\u001B'              -> ansiState = AnsiState.ESCAPE
                    '\r'                  -> curCol = 0
                    '\n'                  -> lineFeed()
                    '\b'                  -> { if (curCol > 0) curCol-- }
                    '\t'                  -> { curCol = ((curCol / 8) + 1) * 8
                                               if (curCol >= termCols) curCol = termCols - 1 }
                    '\u0007'              -> { /* BEL */ }
                    '\u000E', '\u000F'    -> { /* SO/SI: ignore */ }
                    // C1 control codes (8-bit equivalents of ESC sequences)
                    '\u009B'              -> { ansiState = AnsiState.CSI; csiParams.clear() }
                    '\u009D'              -> ansiState = AnsiState.OSC
                    '\u0090'              -> ansiState = AnsiState.DCS
                    in '\u0080'..'\u009F' -> { /* other C1: silently ignore */ }
                    else                  -> if (ch >= ' ') printChar(ch)
                }
                AnsiState.ESCAPE -> {
                    ansiState = AnsiState.NORMAL
                    when (ch) {
                        '['              -> { ansiState = AnsiState.CSI; csiParams.clear() }
                        ']'              -> ansiState = AnsiState.OSC
                        'P', 'X', '^', '_' -> ansiState = AnsiState.DCS
                        'M'              -> reverseIndex()
                        '7'              -> { savedRow = curRow; savedCol = curCol }
                        '8'              -> {
                            curRow = savedRow.coerceIn(0, termRows - 1)
                            curCol = savedCol.coerceIn(0, termCols - 1)
                        }
                        'c'              -> fullReset()
                        '(', ')', '*', '+' -> ansiState = AnsiState.CHARSET
                        // ESC \ = ST terminator — already consumed here, stays NORMAL
                    }
                }
                AnsiState.CHARSET -> {
                    // Consume charset designator byte ('B' = ASCII, '0' = graphics, etc.)
                    ansiState = AnsiState.NORMAL
                }
                AnsiState.CSI -> {
                    if (ch.isDigit() || ch == ';' || ch == '?' ||
                        ch == '>' || ch == '<' || ch == '!' || ch == '=' || ch == '$') {
                        csiParams.append(ch)
                    } else {
                        handleCSI(ch, csiParams.toString())
                        ansiState = AnsiState.NORMAL
                    }
                }
                AnsiState.OSC, AnsiState.DCS -> {
                    when (ch) {
                        '\u0007' -> ansiState = AnsiState.NORMAL   // BEL terminates OSC
                        '\u001B' -> ansiState = AnsiState.ESCAPE   // ESC \ (ST) — consume \
                    }
                    // all other payload bytes silently consumed
                }
            }
        }
    }

    private fun printChar(ch: Char) = printChar(ch.toString())

    private fun printChar(ch: String) {
        if (curCol >= termCols) { curCol = 0; lineFeed() }
        screen[curRow][curCol].apply {
            this.ch = ch
            fg = fgColor; bg = bgColor
            bold = isBold; underline = isUnderline; reverse = isReverse
        }
        curCol++
    }

    private fun lineFeed() {
        if (curRow < scrollBot) curRow++
        else scrollRegionUp()
    }

    private fun scrollRegionUp() {
        if (scrollTop == 0 && !altScreenActive) {
            scrollback.addLast(screen[0])
            if (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
        }
        for (r in scrollTop until scrollBot) screen[r] = screen[r + 1]
        screen[scrollBot] = blankRow()
    }

    private fun scrollRegionDown() {
        for (r in scrollBot downTo scrollTop + 1) screen[r] = screen[r - 1]
        screen[scrollTop] = blankRow()
    }

    private fun reverseIndex() {
        if (curRow > scrollTop) curRow-- else scrollRegionDown()
    }

    private fun insertLinesAtCursor(n: Int) {
        repeat(n) {
            for (r in scrollBot downTo curRow + 1) screen[r] = screen[r - 1]
            screen[curRow] = blankRow()
        }
    }

    private fun deleteLinesAtCursor(n: Int) {
        repeat(n) {
            for (r in curRow until scrollBot) screen[r] = screen[r + 1]
            screen[scrollBot] = blankRow()
        }
    }

    // ── CSI dispatch ──────────────────────────────────────────────────────────

    private fun handleCSI(cmd: Char, params: String) {
        val isPrivate = params.startsWith("?")
        val cleaned   = if (isPrivate) params.substring(1) else params
        val parts     = cleaned.split(";")
        val n1 = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val n0 = parts.getOrNull(0)?.toIntOrNull() ?: 0

        when (cmd) {
            'A' -> curRow = (curRow - n1).coerceAtLeast(0)
            'B' -> curRow = (curRow + n1).coerceAtMost(termRows - 1)
            'C' -> curCol = (curCol + n1).coerceAtMost(termCols - 1)
            'D' -> curCol = (curCol - n1).coerceAtLeast(0)
            'E' -> { curRow = (curRow + n1).coerceAtMost(termRows - 1); curCol = 0 }
            'F' -> { curRow = (curRow - n1).coerceAtLeast(0);           curCol = 0 }
            'G' -> curCol = (n1 - 1).coerceIn(0, termCols - 1)
            'H', 'f' -> {
                curRow = ((parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceIn(0, termRows - 1)
                curCol = ((parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1).coerceIn(0, termCols - 1)
            }
            'J' -> when (n0) {
                0    -> { eraseLineEnd(); for (r in curRow + 1 until termRows) screen[r] = blankRow() }
                1    -> { for (r in 0 until curRow) screen[r] = blankRow(); eraseLineStart() }
                2, 3 -> {
                    if (!altScreenActive) {
                        for (r in 0..curRow) scrollback.addLast(screen[r])
                        while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
                    }
                    screen = newScreen()
                }
            }
            'K' -> when (n0) {
                0 -> eraseLineEnd()
                1 -> eraseLineStart()
                2 -> screen[curRow] = blankRow()
            }
            'L' -> insertLinesAtCursor(n1)
            'M' -> deleteLinesAtCursor(n1)
            'P' -> deleteChars(n1)
            '@' -> insertBlanks(n1)
            'S' -> repeat(n1) { scrollRegionUp() }
            'T' -> repeat(n1) { scrollRegionDown() }
            'X' -> {
                val end = min(curCol + n1, termCols)
                for (c in curCol until end) screen[curRow][c] = Cell()
            }
            'r' -> {
                val top = ((parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceIn(0, termRows - 1)
                val bot = ((parts.getOrNull(1)?.toIntOrNull() ?: termRows) - 1).coerceIn(0, termRows - 1)
                if (top < bot) { scrollTop = top; scrollBot = bot }
                curRow = 0; curCol = 0
            }
            'm' -> handleSGR(cleaned)
            'h' -> if (isPrivate) handleMode(n1, true)
            'l' -> if (isPrivate) handleMode(n1, false)
            's' -> { savedRow = curRow; savedCol = curCol }
            'u' -> {
                curRow = savedRow.coerceIn(0, termRows - 1)
                curCol = savedCol.coerceIn(0, termCols - 1)
            }
            // 'n', 't', others: silently ignore
        }
    }

    private fun handleMode(mode: Int, enable: Boolean) {
        when (mode) {
            1        -> appCursorKeys  = enable
            25       -> cursorHidden   = !enable
            47, 1049 -> if (enable) enterAltScreen() else leaveAltScreen()
            2004     -> bracketedPasteMode = enable
        }
    }

    private fun enterAltScreen() {
        if (altScreenActive) return
        savedMainScreen = screen
        savedMainRow = curRow; savedMainCol = curCol
        screen = newScreen()
        curRow = 0; curCol = 0
        scrollTop = 0; scrollBot = termRows - 1
        altScreenActive = true
    }

    private fun leaveAltScreen() {
        if (!altScreenActive) return
        screen = savedMainScreen ?: newScreen()
        savedMainScreen = null
        altScreenActive = false
        curRow = savedMainRow.coerceIn(0, termRows - 1)
        curCol = 0   // force col 0 so shell prompt starts at line beginning
        // Erase rows below cursor to prevent stale pre-TUI content from showing
        for (r in curRow + 1 until termRows) screen[r] = blankRow()
        scrollTop = 0; scrollBot = termRows - 1
    }

    private fun fullReset() {
        screen = newScreen(); scrollback.clear()
        curRow = 0; curCol = 0; savedRow = 0; savedCol = 0
        savedMainRow = 0; savedMainCol = 0
        scrollTop = 0; scrollBot = termRows - 1
        altScreenActive = false; savedMainScreen = null
        appCursorKeys = false; cursorHidden = false; bracketedPasteMode = false
        resetStyle()
    }

    private fun eraseLineEnd()   { for (c in curCol until termCols) screen[curRow][c] = Cell() }
    private fun eraseLineStart() { for (c in 0..curCol) screen[curRow][c] = Cell() }

    private fun deleteChars(n: Int) {
        val row   = screen[curRow]
        val shift = min(n, termCols - curCol)
        for (c in curCol until termCols - shift) row[c] = row[c + shift].copy()
        for (c in termCols - shift until termCols) row[c] = Cell()
    }

    private fun insertBlanks(n: Int) {
        val row   = screen[curRow]
        val shift = min(n, termCols - curCol)
        for (c in termCols - 1 downTo curCol + shift) row[c] = row[c - shift].copy()
        for (c in curCol until curCol + shift) row[c] = Cell()
    }

    // ── SGR ───────────────────────────────────────────────────────────────────

    private fun handleSGR(params: String) {
        if (params.isEmpty()) { resetStyle(); return }
        val parts = params.split(";")
        var i = 0
        while (i < parts.size) {
            when (val p = parts[i].toIntOrNull() ?: 0) {
                0       -> resetStyle()
                1       -> isBold = true
                2       -> { /* dim: no dedicated rendering */ }
                3       -> { /* italic: skip */ }
                4       -> isUnderline = true
                5, 6    -> { /* blink: ignore */ }
                7       -> isReverse = true
                8       -> { /* conceal: skip */ }
                9       -> { /* strikethrough: skip */ }
                21, 24  -> isUnderline = false
                22      -> isBold = false
                23      -> { /* italic off */ }
                27      -> isReverse = false
                28      -> { /* reveal */ }
                39      -> fgColor = DEFAULT_FG
                49      -> bgColor = DEFAULT_BG
                in 30..37   -> fgColor = ansi16(p - 30)
                in 40..47   -> bgColor = ansi16(p - 40)
                in 90..97   -> fgColor = ansi16(p - 90 + 8)
                in 100..107 -> bgColor = ansi16(p - 100 + 8)
                38 -> when (parts.getOrNull(i + 1)?.toIntOrNull()) {
                    5 -> { fgColor = xterm256(parts.getOrNull(i + 2)?.toIntOrNull() ?: 0); i += 2 }
                    2 -> {
                        fgColor = Color.rgb(
                            parts.getOrNull(i + 2)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 3)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 4)?.toIntOrNull() ?: 0); i += 4
                    }
                }
                48 -> when (parts.getOrNull(i + 1)?.toIntOrNull()) {
                    5 -> { bgColor = xterm256(parts.getOrNull(i + 2)?.toIntOrNull() ?: 0); i += 2 }
                    2 -> {
                        bgColor = Color.rgb(
                            parts.getOrNull(i + 2)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 3)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 4)?.toIntOrNull() ?: 0); i += 4
                    }
                }
            }
            i++
        }
    }

    private fun resetStyle() {
        fgColor = DEFAULT_FG; bgColor = DEFAULT_BG
        isBold = false; isUnderline = false; isReverse = false
    }

    private fun ansi16(i: Int): Int {
        val c = intArrayOf(
            0x000000, 0xAA0000, 0x00AA00, 0xAA5500, 0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA,
            0x555555, 0xFF5555, 0x55FF55, 0xFFFF55, 0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF
        )[i.coerceIn(0, 15)]
        return Color.rgb(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
    }

    private fun xterm256(i: Int): Int {
        if (i < 16) return ansi16(i)
        if (i >= 232) { val v = 8 + (i - 232) * 10; return Color.rgb(v, v, v) }
        val idx = i - 16
        fun comp(c: Int) = if (c == 0) 0 else c * 40 + 55
        return Color.rgb(comp(idx / 36), comp((idx % 36) / 6), comp(idx % 6))
    }

    // ── Text selection ────────────────────────────────────────────────────────

    private fun touchToLineCol(x: Float, y: Float): Pair<Int, Int> {
        val visualRow = (y / cellH).toInt().coerceIn(0, termRows - 1)
        val col       = ((x / cellW).toInt() + scrollColOff).coerceIn(0, termCols - 1)
        return Pair(computeFirstLine() + visualRow, col)
    }

    private fun startSelectionAt(x: Float, y: Float) {
        val (line, col) = touchToLineCol(x, y)
        selStartLine = line; selStartCol = col
        selEndLine   = line; selEndCol   = col
        selActive = true
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        selActionMode?.finish()
        selActionMode = startActionMode(selCallback, ActionMode.TYPE_FLOATING)
    }

    private fun clearSelection() {
        selActive = false
        invalidate()
    }

    private fun normalizedSel(): IntArray {
        val fwd = selStartLine < selEndLine ||
                  (selStartLine == selEndLine && selStartCol <= selEndCol)
        return if (fwd) intArrayOf(selStartLine, selStartCol, selEndLine, selEndCol)
               else     intArrayOf(selEndLine,   selEndCol,   selStartLine, selStartCol)
    }

    private fun isInSelection(lineIdx: Int, col: Int): Boolean {
        if (!selActive) return false
        val ns = normalizedSel()
        val sl = ns[0]; val sc = ns[1]; val el = ns[2]; val ec = ns[3]
        return when {
            lineIdx < sl || lineIdx > el -> false
            lineIdx == sl && lineIdx == el -> col in sc..ec
            lineIdx == sl -> col >= sc
            lineIdx == el -> col <= ec
            else -> true
        }
    }

    private fun lineByIndex(li: Int): Array<Cell>? = when {
        li < 0              -> null
        li < scrollback.size -> scrollback[li]
        else                -> { val sr = li - scrollback.size; if (sr < termRows) screen[sr] else null }
    }

    private fun buildSelectedText(): String {
        if (!selActive) return ""
        val ns = normalizedSel()
        val sl = ns[0]; val sc = ns[1]; val el = ns[2]; val ec = ns[3]
        val sb = StringBuilder()
        for (li in sl..el) {
            val row = lineByIndex(li) ?: continue
            val from = if (li == sl) sc else 0
            val to   = if (li == el) ec else termCols - 1
            var line = ""
            for (c in from..to.coerceAtMost(row.size - 1)) line += row[c].ch
            sb.append(line.trimEnd())
            if (li < el) sb.append('\n')
        }
        return sb.toString()
    }

    private fun copyToClipboard() {
        val text = buildSelectedText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
    }

    fun pasteFromClipboard() {
        val cm   = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        pasteText(text)
    }

    fun pasteText(text: String) {
        val out = if (bracketedPasteMode)
            ("\u001B[200~" + text.replace("\n", "\r") + "\u001B[201~").toByteArray(Charsets.UTF_8)
        else
            text.replace("\n", "\r").toByteArray(Charsets.UTF_8)
        onKeyInput?.invoke(out)
    }

    fun getSelectedText(): String = buildSelectedText()

    private val selCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, ID_COPY,  0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, ID_PASTE, 1, android.R.string.paste)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY  -> { copyToClipboard(); mode.finish(); return true }
                ID_PASTE -> { pasteFromClipboard(); mode.finish(); return true }
            }
            return false
        }
        override fun onDestroyActionMode(mode: ActionMode) {
            selActionMode = null
            clearSelection()
        }
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val fl = computeFirstLine()
            val r1 = (selStartLine - fl).coerceIn(0, termRows - 1)
            val r2 = (selEndLine   - fl).coerceIn(0, termRows - 1)
            outRect.set(0, (min(r1, r2) * cellH).toInt(),
                        width, ((max(r1, r2) + 1) * cellH).toInt())
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchX0 = ev.x; touchY0 = ev.y
                touchX  = ev.x; touchY  = ev.y
                touchMoved = false; touchScrollFrac = 0f
                lpX = ev.x; lpY = ev.y
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - touchX; val dy = ev.y - touchY
                if (!touchMoved && (abs(ev.x - touchX0) > 8f || abs(ev.y - touchY0) > 8f)) {
                    touchMoved = true
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!selActive) {
                        val dxTotal = ev.x - touchX0
                        val dyTotal = ev.y - touchY0
                        val isHoriz = abs(dxTotal) > abs(dyTotal)
                        if (isHoriz && !horizontalScrollEnabled) {
                            // let ViewPager handle horizontal swipes
                        } else {
                            val maxColOff = max(0, termCols - (viewW / cellW).toInt())
                            val atEdge = isHoriz && (
                                (dxTotal > 0 && scrollColOff == 0) ||
                                (dxTotal < 0 && scrollColOff >= maxColOff)
                            )
                            if (!atEdge) parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
                if (selActive) {
                    // Extend selection to finger position
                    val (line, col) = touchToLineCol(ev.x, ev.y)
                    selEndLine = line; selEndCol = col
                    invalidate()
                    selActionMode?.invalidateContentRect()
                } else if (touchMoved) {
                    // Scroll
                    touchScrollFrac += -dy / cellH
                    val steps = touchScrollFrac.toInt()
                    if (steps != 0) {
                        scrollRowOff = (scrollRowOff - steps).coerceIn(0, scrollback.size)
                        touchScrollFrac -= steps
                        invalidate()
                    }
                    if (horizontalScrollEnabled) {
                        val maxColOff = max(0, termCols - (viewW / cellW).toInt())
                        val colStep = (-dx / cellW).toInt()
                        if (colStep != 0) {
                            scrollColOff = (scrollColOff + colStep).coerceIn(0, maxColOff)
                            invalidate()
                        }
                    }
                    touchX = ev.x; touchY = ev.y
                }
            }
            MotionEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                when {
                    selActive && !touchMoved -> selActionMode?.finish()   // tap = cancel sel
                    !touchMoved && !selActive -> focusAndShowKeyboard()
                }
            }
        }
        return true
    }

    // ── Key dispatch ──────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isShiftPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        scrollRowOff = (scrollRowOff + termRows).coerceAtMost(scrollback.size)
                        invalidate(); return true
                    }
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        scrollRowOff = (scrollRowOff - termRows).coerceAtLeast(0)
                        invalidate(); return true
                    }
                }
            }
            val bytes = keyCodeToBytes(event.keyCode, event)
            if (bytes != null) { onKeyInput?.invoke(bytes); return true }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.isShiftPressed && event.keyCode in
                    listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)) return true
            if (keyCodeToBytes(event.keyCode, event) != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blinkHandler.post(blinkTask)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkHandler.removeCallbacksAndMessages(null)
        longPressHandler.removeCallbacksAndMessages(null)
        selActionMode?.finish()
    }

    // ── IME ───────────────────────────────────────────────────────────────────

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection().also { it.resetToSentinel() }
    }

    private fun keyCodeToBytes(keyCode: Int, event: KeyEvent): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER      -> byteArrayOf(13)
        KeyEvent.KEYCODE_DEL        -> byteArrayOf(127)
        KeyEvent.KEYCODE_TAB        -> byteArrayOf(9)
        KeyEvent.KEYCODE_ESCAPE     -> byteArrayOf(27)
        KeyEvent.KEYCODE_DPAD_UP    -> cursorKeyBytes('A')
        KeyEvent.KEYCODE_DPAD_DOWN  -> cursorKeyBytes('B')
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKeyBytes('C')
        KeyEvent.KEYCODE_DPAD_LEFT  -> cursorKeyBytes('D')
        KeyEvent.KEYCODE_MOVE_HOME  -> byteArrayOf(27, '['.code.toByte(), 'H'.code.toByte())
        KeyEvent.KEYCODE_MOVE_END   -> byteArrayOf(27, '['.code.toByte(), 'F'.code.toByte())
        KeyEvent.KEYCODE_PAGE_UP    -> byteArrayOf(27, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_PAGE_DOWN  -> byteArrayOf(27, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_F1         -> fnKey("OP")
        KeyEvent.KEYCODE_F2         -> fnKey("OQ")
        KeyEvent.KEYCODE_F3         -> fnKey("OR")
        KeyEvent.KEYCODE_F4         -> fnKey("OS")
        KeyEvent.KEYCODE_F5         -> fnKey("[15~")
        KeyEvent.KEYCODE_F6         -> fnKey("[17~")
        KeyEvent.KEYCODE_F7         -> fnKey("[18~")
        KeyEvent.KEYCODE_F8         -> fnKey("[19~")
        KeyEvent.KEYCODE_F9         -> fnKey("[20~")
        KeyEvent.KEYCODE_F10        -> fnKey("[21~")
        KeyEvent.KEYCODE_F11        -> fnKey("[23~")
        KeyEvent.KEYCODE_F12        -> fnKey("[24~")
        else -> event.unicodeChar.takeIf { it > 0 }?.let { byteArrayOf(it.toByte()) }
    }

    private fun fnKey(seq: String) = byteArrayOf(27) + seq.toByteArray(Charsets.US_ASCII)

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, true) {

        private val SENTINEL = " "

        internal fun resetToSentinel() {
            val e = editable ?: return
            e.replace(0, e.length, SENTINEL)
            android.text.Selection.setSelection(e, SENTINEL.length)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            val str = editable?.toString()?.removePrefix(SENTINEL) ?: return true
            if (str.isNotEmpty()) onKeyInput?.invoke(encode(str))
            resetToSentinel()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            val str = editable?.toString()?.removePrefix(SENTINEL) ?: return true
            if (str.isNotEmpty()) onKeyInput?.invoke(encode(str))
            resetToSentinel()
            return true
        }

        private fun encode(str: String): ByteArray {
            val s = str.replace("\n", "\r")
            return if (bracketedPasteMode)
                ("\u001B[200~$s\u001B[201~").toByteArray(Charsets.UTF_8)
            else
                s.toByteArray(Charsets.UTF_8)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceIn(0, 16)) { onKeyInput?.invoke(byteArrayOf(127)) }
            resetToSentinel()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val bytes = keyCodeToBytes(event.keyCode, event)
                if (bytes != null) { onKeyInput?.invoke(bytes); return true }
            }
            return super.sendKeyEvent(event)
        }
    }
}
