package hu.billman.konsolessh.terminal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
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
 * A terminál teljes logikai állapotát (cellarács, scrollback, kurzor, ANSI-state,
 * SGR-stílus, módok) a [TerminalBuffer] tartja. Ez az osztály felelős:
 *   - renderelésért (Canvas + Paint)
 *   - input-kezelésért (touch, key, IME)
 *   - view-szintű görgetésért (scrollRowOff/scrollColOff)
 *   - szöveg-szelekcióért + clipboard
 *   - kurzor-villogásért
 *
 * A Buffer ChangeListener-e automatikusan újrarajzolást kér, így a View nem
 * felejti el a rajzot újra kérni. Az [append] bármely szálról hívható: a
 * bájtok feldolgozása a hívó szálán fut, a rajzolás a buffer zárja alatt olvas.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onKeyInput: ((ByteArray) -> Unit)? = null
    var onTerminalResize: ((cols: Int, rows: Int) -> Unit)? = null

    /** A terminál válasza egy lekérdezésre (pl. kurzorpozíció); háttérszálról is jöhet. */
    var onTerminalResponse: ((ByteArray) -> Unit)? = null
    var horizontalScrollEnabled = true
    val fontSize: Float get() = fontSizeSp

    val currentCols get() = buffer.cols
    val currentRows get() = buffer.rows

    companion object {
        private val DEFAULT_BG = Color.BLACK
        private val SELECTION_BG = Color.argb(160, 70, 130, 220)
        private const val MIN_COLS = 80
        private const val LONG_PRESS_MS = 400L
        private const val CURSOR_ON_MS = 600L
        private const val CURSOR_OFF_MS = 300L
        private const val MIN_FONT_SP = 6f
        private const val MAX_FONT_SP = 40f
        private const val ID_COPY = 1
        private const val ID_PASTE = 2
        private const val OPAQUE = 0xFF shl 24
        private const val LOW_24 = 0xFFFFFFL
        private const val LOW_32 = 0xFFFFFFFFL
        private const val BG_SHIFT = 2
        private const val FG_SHIFT = 34
        private const val LOOK_BOLD = 1L
        private const val LOOK_UNDERLINE = 2L

        /** Eddig a kódpontig (Latin-1 + Latin Extended-A/B) próbál futamban rajzolni. */
        private const val FIXED_ADVANCE_LIMIT = 0x250
        private const val ADVANCE_TOLERANCE = 0.05f
    }

    // ── Logikai állapot: minden a Bufferben ──────────────────────────────────

    internal val buffer = TerminalBuffer(initialCols = MIN_COLS, initialRows = 24)

    // ── View-méretek ─────────────────────────────────────────────────────────

    private var viewW = 0
    private var viewH = 0

    // ── Font & painting ──────────────────────────────────────────────────────

    private var fontSizeSp = 12f
    private var firstLayout = true
    private val bgPaint = Paint()
    private val normPaint = Paint().apply {
        typeface = loadTypeface(context)
        isAntiAlias = true
    }
    private val boldPaint = Paint().apply {
        typeface = Typeface.create(loadTypeface(context), Typeface.BOLD)
        isAntiAlias = true
    }
    private var runChars = CharArray(MIN_COLS)
    private val normAdvance = ByteArray(FIXED_ADVANCE_LIMIT)
    private val boldAdvance = ByteArray(FIXED_ADVANCE_LIMIT)
    private var cellW = 1f
    private var cellH = 1f
    private var cellBase = 0f
    private val cellRect = RectF()

    // ── View-scroll ──────────────────────────────────────────────────────────

    private var scrollRowOff = 0

    /** Új kimenet érkezett: a következő rajzolás az aljára görget. */
    @Volatile private var followOutput = false
    private var scrollColOff = 0
    private var touchScrollFrac = 0f

    // ── Szöveg-szelekció ─────────────────────────────────────────────────────

    private var selActive = false
    private var selStartLine = 0
    private var selStartCol = 0
    private var selEndLine = 0
    private var selEndCol = 0
    private var selActionMode: ActionMode? = null

    private var lpX = 0f
    private var lpY = 0f
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { startSelectionAt(lpX, lpY) }

    // ── Kurzor-villogás ──────────────────────────────────────────────────────

    private var cursorOn = true
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkTask = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            blinkHandler.postDelayed(this, if (cursorOn) CURSOR_ON_MS else CURSOR_OFF_MS)
        }
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private var touchX0 = 0f
    private var touchY0 = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touchMoved = false

    init {
        setBackgroundColor(DEFAULT_BG)
        isFocusable = true
        isFocusableInTouchMode = true
        applyFontMetrics()
        // A Buffer minden state-változás után értesít, akár háttérszálról is;
        // a postInvalidateOnAnimation szálbiztos, és képkockánként egyszer rajzol.
        buffer.setChangeListener { postInvalidateOnAnimation() }
        buffer.setResponseListener { bytes -> onTerminalResponse?.invoke(bytes) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
        cellH = fm.descent - fm.ascent
        cellBase = -fm.ascent
        cellW = normPaint.measureText("M")
        normAdvance.fill(0)
        boldAdvance.fill(0)
    }

    /**
     * Annak a logikai sornak az indexe, amely a 0. vizuális sorra kerül.
     * Bottom-gravity: ha nincs scrollback és a néző nem görgetett, a tartalom
     * a képernyő aljához igazodik; különben a scrollback-et elgurítjuk.
     */
    private fun computeFirstLine(): Int {
        val scrollbackSize = buffer.scrollbackSize
        val rows = buffer.rows
        return if (scrollbackSize == 0 && scrollRowOff == 0 && !buffer.altScreenActive) {
            buffer.cursorRow - (rows - 1)
        } else {
            scrollbackSize - scrollRowOff
        }
    }

    // ── Méretezés ────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        viewW = w
        viewH = h
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
            max(1, (h / cellH).toInt()),
        )
    }

    private fun resizeTerm(nc: Int, nr: Int) {
        if (nc == buffer.cols && nr == buffer.rows) return
        buffer.resize(nc, nr)
        onTerminalResize?.invoke(buffer.cols, buffer.rows)
    }

    // ── Rajzolás ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(DEFAULT_BG)
        if (followOutput) {
            followOutput = false
            scrollRowOff = 0
        }
        buffer.withLock { drawCells(canvas) }
    }

    /** Egy rajzolás állandó adatai; a kijelölés határai egyszer számolódnak. */
    private class Frame(
        val scrollbackSize: Int,
        val cursorRow: Int,
        val cursorCol: Int,
        val showCursor: Boolean,
        val selection: IntArray?,
    )

    private fun drawCells(canvas: Canvas) {
        val frame = Frame(
            scrollbackSize = buffer.scrollbackSize,
            cursorRow = buffer.cursorRow,
            cursorCol = buffer.cursorCol,
            showCursor = !buffer.cursorHidden && scrollRowOff == 0 && cursorOn,
            selection = if (selActive) normalizedSel() else null,
        )
        val firstLine = computeFirstLine()
        for (r in 0 until buffer.rows) {
            val lineIdx = firstLine + r
            val row = if (lineIdx < 0) null else buffer.lineAt(lineIdx)
            if (row != null) drawRow(canvas, row, lineIdx, r * cellH, frame)
        }
    }

    // Adjacent cells with the same look form a run: one background rectangle
    // and, for fixed-advance glyphs, one drawText call instead of one per cell.
    private fun drawRow(canvas: Canvas, row: TermLine, lineIdx: Int, y: Float, frame: Frame) {
        val cursorCol = if (frame.showCursor && lineIdx - frame.scrollbackSize == frame.cursorRow) frame.cursorCol else -1
        val cTo = min(row.size, scrollColOff + buffer.cols + 1)
        var c = scrollColOff
        while (c < cTo) {
            val look = lookAt(row, lineIdx, c, cursorCol, frame.selection)
            var end = c + 1
            while (end < cTo && lookAt(row, lineIdx, end, cursorCol, frame.selection) == look) end++
            drawRun(canvas, row, c, end, y, look)
            c = end
        }
    }

    /**
     * A cella megjelenése egy Long-ban: előtér (RGB, mindig átlátszatlan),
     * háttér (ARGB, a kijelölés áttetsző), félkövér, aláhúzás.
     */
    private fun lookAt(row: TermLine, lineIdx: Int, c: Int, cursorCol: Int, selection: IntArray?): Long {
        val cell = row[c]
        val fg = if (cell.reverse) cell.bg.argb else cell.fg.argb
        val bg = if (cell.reverse) cell.fg.argb else cell.bg.argb
        val isCursor = c == cursorCol
        val effectiveBg = when {
            selection != null && isInSelection(selection, lineIdx, c) -> SELECTION_BG
            isCursor -> fg
            else -> bg
        }
        val effectiveFg = if (isCursor) bg else fg
        val flags = (if (cell.bold) LOOK_BOLD else 0L) or (if (cell.underline) LOOK_UNDERLINE else 0L)
        return ((effectiveFg.toLong() and LOW_24) shl FG_SHIFT) or
            ((effectiveBg.toLong() and LOW_32) shl BG_SHIFT) or flags
    }

    private fun drawRun(canvas: Canvas, row: TermLine, from: Int, to: Int, y: Float, look: Long) {
        val x = (from - scrollColOff) * cellW
        val width = (to - from) * cellW
        val bg = ((look ushr BG_SHIFT) and LOW_32).toInt()
        if (bg != DEFAULT_BG) {
            bgPaint.color = bg
            cellRect.set(x, y, x + width, y + cellH)
            canvas.drawRect(cellRect, bgPaint)
        }
        val paint = if (look and LOOK_BOLD != 0L) boldPaint else normPaint
        paint.color = OPAQUE or ((look ushr FG_SHIFT) and LOW_24).toInt()
        if (!drawFixedAdvanceRun(canvas, row, from, to, x, y, paint)) {
            for (c in from until to) {
                val ch = row[c].ch
                if (ch != " " && ch != TerminalBuffer.WIDE_TAIL) {
                    canvas.drawText(ch, (c - scrollColOff) * cellW, y + cellBase, paint)
                }
            }
        }
        if (look and LOOK_UNDERLINE != 0L) {
            canvas.drawLine(x, y + cellH - 1f, x + width, y + cellH - 1f, paint)
        }
    }

    /** Egy hívással rajzolja a futamot, ha minden karakterének pontosan cellányi a szélessége. */
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
            cache[ch.code] = if (abs(paint.measureText(ch.toString()) - cellW) < ADVANCE_TOLERANCE) 1 else 2
        }
        return cache[ch.code] == 1.toByte()
    }

    // ── Publikus API ─────────────────────────────────────────────────────────

    /**
     * Bármely szálról hívható; nagy kimenetet háttérszálról érdemes.
     * [respond] false: visszajátszott kimenet, a benne lévő lekérdezésekre nem
     * megy válasz (lásd [TerminalBuffer.write]).
     */
    fun append(bytes: ByteArray, length: Int, respond: Boolean = true) {
        followOutput = true
        buffer.write(bytes, length, respond)
        // újrarajzolás: a buffer ChangeListener-e intézi
    }

    fun clear() {
        buffer.clearScreen()
        // invalidate: ChangeListener
    }

    /** Mindent töröl, a görgetési előzményt is (újrarajzolt statikus tartalomhoz). */
    fun resetContent() {
        buffer.reset()
        scrollRowOff = 0
        invalidate()
    }

    fun scrollToBottom() {
        scrollRowOff = 0
        invalidate()
    }

    fun scrollToTop() {
        scrollRowOff = buffer.scrollbackSize
        invalidate()
    }

    fun setFontSize(sp: Float) {
        fontSizeSp = sp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        applyFontMetrics()
        if (viewW > 0 && viewH > 0) {
            resizeTerm(
                max(MIN_COLS, (viewW / cellW).toInt()),
                max(1, (viewH / cellH).toInt()),
            )
        }
    }

    fun zoom(delta: Float) {
        fontSizeSp = (fontSizeSp + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        applyFontMetrics()
        if (viewW > 0 && viewH > 0) {
            resizeTerm(
                max(MIN_COLS, (viewW / cellW).toInt()),
                max(1, (viewH / cellH).toInt()),
            )
        }
        invalidate()
    }

    /** Csak fókusz kérése — nem nyitja meg a soft-keyboardot. */
    fun focusInput() {
        post { requestFocus() }
    }

    /** Fókusz + soft-keyboard nyitás (explicit felhasználói szándék). */
    fun focusAndShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService<InputMethodManager>() ?: return
        if (!imm.showSoftInput(this, 0)) {
            post { imm.showSoftInput(this, 0) }
        }
    }

    fun cursorKeyBytes(dir: Char): ByteArray = if (buffer.appCursorKeys) {
        byteArrayOf(27, 'O'.code.toByte(), dir.code.toByte())
    } else {
        byteArrayOf(27, '['.code.toByte(), dir.code.toByte())
    }

    // ── Szöveg-szelekció ─────────────────────────────────────────────────────

    private fun touchToLineCol(x: Float, y: Float): Pair<Int, Int> {
        val visualRow = (y / cellH).toInt().coerceIn(0, buffer.rows - 1)
        val col = ((x / cellW).toInt() + scrollColOff).coerceIn(0, buffer.cols - 1)
        return Pair(computeFirstLine() + visualRow, col)
    }

    private fun startSelectionAt(x: Float, y: Float) {
        val (line, col) = touchToLineCol(x, y)
        selStartLine = line; selStartCol = col
        selEndLine = line; selEndCol = col
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
        return if (fwd) {
            intArrayOf(selStartLine, selStartCol, selEndLine, selEndCol)
        } else {
            intArrayOf(selEndLine, selEndCol, selStartLine, selStartCol)
        }
    }

    private fun isInSelection(ns: IntArray, lineIdx: Int, col: Int): Boolean {
        val sl = ns[0]; val sc = ns[1]; val el = ns[2]; val ec = ns[3]
        return when {
            lineIdx < sl || lineIdx > el -> false
            lineIdx == sl && lineIdx == el -> col in sc..ec
            lineIdx == sl -> col >= sc
            lineIdx == el -> col <= ec
            else -> true
        }
    }

    private fun buildSelectedText(): String = buffer.withLock { selectedTextLocked() }

    private fun selectedTextLocked(): String {
        if (!selActive) return ""
        val ns = normalizedSel()
        val sl = ns[0]; val sc = ns[1]; val el = ns[2]; val ec = ns[3]
        val sb = StringBuilder()
        val cols = buffer.cols
        for (li in sl..el) {
            val row = buffer.lineAt(li) ?: continue
            val from = if (li == sl) sc else 0
            val to = if (li == el) ec else cols - 1
            var line = ""
            for (c in from..to.coerceAtMost(row.size - 1)) line += row[c].ch // WIDE_TAIL is ""
            sb.append(line.trimEnd())
            if (li < el) sb.append('\n')
        }
        return sb.toString()
    }

    private fun copyToClipboard() {
        val text = buildSelectedText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = ClipData.newPlainText("terminal", text)
            // Terminálból gyakran token vagy jelszó kerül a vágólapra: a rendszer
            // ilyenkor nem mutat előnézetet. API 33 alatt a dokumentált kulcsnév kell.
            val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            clip.description.extras = PersistableBundle().apply { putBoolean(sensitiveKey, true) }
            cm.setPrimaryClip(clip)
        }
    }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        pasteText(text)
    }

    fun pasteText(text: String) {
        val out = if (buffer.bracketedPasteMode) {
            ("\u001B[200~" + text.replace("\n", "\r") + "\u001B[201~").toByteArray(Charsets.UTF_8)
        } else {
            text.replace("\n", "\r").toByteArray(Charsets.UTF_8)
        }
        onKeyInput?.invoke(out)
    }

    fun getSelectedText(): String = buildSelectedText()

    private val selCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, ID_COPY, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, ID_PASTE, 1, android.R.string.paste)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> { copyToClipboard(); mode.finish(); return true }
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
            val r1 = (selStartLine - fl).coerceIn(0, buffer.rows - 1)
            val r2 = (selEndLine - fl).coerceIn(0, buffer.rows - 1)
            outRect.set(
                0,
                (min(r1, r2) * cellH).toInt(),
                width,
                ((max(r1, r2) + 1) * cellH).toInt(),
            )
        }
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchX0 = ev.x; touchY0 = ev.y
                touchX = ev.x; touchY = ev.y
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
                            // hagyjuk a ViewPager-nek a horizontális swipe-ot
                        } else {
                            val maxColOff = max(0, buffer.cols - (viewW / cellW).toInt())
                            val atEdge = isHoriz && (
                                (dxTotal > 0 && scrollColOff == 0) ||
                                    (dxTotal < 0 && scrollColOff >= maxColOff)
                                )
                            if (!atEdge) parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
                if (selActive) {
                    val (line, col) = touchToLineCol(ev.x, ev.y)
                    selEndLine = line; selEndCol = col
                    invalidate()
                    selActionMode?.invalidateContentRect()
                } else if (touchMoved) {
                    touchScrollFrac += -dy / cellH
                    val steps = touchScrollFrac.toInt()
                    if (steps != 0) {
                        scrollRowOff = (scrollRowOff - steps).coerceIn(0, buffer.scrollbackSize)
                        touchScrollFrac -= steps
                        invalidate()
                    }
                    if (horizontalScrollEnabled) {
                        val maxColOff = max(0, buffer.cols - (viewW / cellW).toInt())
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
                    selActive && !touchMoved -> selActionMode?.finish()
                    !touchMoved && !selActive -> focusAndShowKeyboard()
                }
            }
        }
        return true
    }

    // ── Key dispatch ─────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isShiftPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        scrollRowOff = (scrollRowOff + buffer.rows).coerceAtMost(buffer.scrollbackSize)
                        invalidate(); return true
                    }
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        scrollRowOff = (scrollRowOff - buffer.rows).coerceAtLeast(0)
                        invalidate(); return true
                    }
                }
            }
            val bytes = keyCodeToBytes(event.keyCode, event)
            if (bytes != null) { onKeyInput?.invoke(bytes); return true }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.isShiftPressed && event.keyCode in
                listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
            ) return true
            if (keyCodeToBytes(event.keyCode, event) != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

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

    // ── IME ──────────────────────────────────────────────────────────────────

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection().also { it.resetToSentinel() }
    }

    private fun keyCodeToBytes(keyCode: Int, event: KeyEvent): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> byteArrayOf(13)
        // Android KEYCODE_DEL neve félrevezető: ez valójában a Backspace billentyű.
        KeyEvent.KEYCODE_DEL -> byteArrayOf(127)
        // A tényleges "Delete" (forward delete) billentyű: xterm Del-szekvencia.
        KeyEvent.KEYCODE_FORWARD_DEL -> KeySequences.escape("[3~")
        KeyEvent.KEYCODE_TAB -> byteArrayOf(KeySequences.TAB)
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(KeySequences.ESC)
        KeyEvent.KEYCODE_DPAD_UP -> cursorKeyBytes('A')
        KeyEvent.KEYCODE_DPAD_DOWN -> cursorKeyBytes('B')
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKeyBytes('C')
        KeyEvent.KEYCODE_DPAD_LEFT -> cursorKeyBytes('D')
        KeyEvent.KEYCODE_MOVE_HOME -> byteArrayOf(27, '['.code.toByte(), 'H'.code.toByte())
        KeyEvent.KEYCODE_MOVE_END -> byteArrayOf(27, '['.code.toByte(), 'F'.code.toByte())
        KeyEvent.KEYCODE_PAGE_UP -> byteArrayOf(27, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_PAGE_DOWN -> byteArrayOf(27, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            KeySequences.escape(KeySequences.FUNCTION_KEYS[keyCode - KeyEvent.KEYCODE_F1])
        else -> event.unicodeChar.takeIf { it > 0 }?.let { byteArrayOf(it.toByte()) }
    }

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
            return if (buffer.bracketedPasteMode) {
                ("\u001B[200~$s\u001B[201~").toByteArray(Charsets.UTF_8)
            } else {
                s.toByteArray(Charsets.UTF_8)
            }
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
