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
 * A terminál teljes logikai állapotát (cellarács, scrollback, kurzor, ANSI-state,
 * SGR-stílus, módok) a [TerminalBuffer] tartja. Ez az osztály felelős:
 *   - renderelésért (Canvas + Paint)
 *   - input-kezelésért (touch, key, IME)
 *   - view-szintű görgetésért (scrollRowOff/scrollColOff)
 *   - szöveg-szelekcióért + clipboard
 *   - kurzor-villogásért
 *
 * A Buffer ChangeListener-e automatikusan invalidate()-el, így a View nem
 * felejti el a rajzot újra kérni.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onKeyInput: ((ByteArray) -> Unit)? = null
    var onTerminalResize: ((cols: Int, rows: Int) -> Unit)? = null
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
    private var cellW = 1f
    private var cellH = 1f
    private var cellBase = 0f
    private val cellRect = RectF()

    // ── View-scroll ──────────────────────────────────────────────────────────

    private var scrollRowOff = 0
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
        // A Buffer minden state-változás után értesít, a View automatikusan újrarajzol.
        buffer.setChangeListener { invalidate() }
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

        val rows = buffer.rows
        val cols = buffer.cols
        val scrollbackSize = buffer.scrollbackSize
        val cursorRow = buffer.cursorRow
        val cursorCol = buffer.cursorCol
        val cursorHidden = buffer.cursorHidden
        val firstLine = computeFirstLine()

        for (r in 0 until rows) {
            val lineIdx = firstLine + r
            if (lineIdx < 0) continue
            val row = buffer.lineAt(lineIdx) ?: continue

            val screenRow = if (lineIdx < scrollbackSize) -1 else lineIdx - scrollbackSize

            val y = r * cellH
            val cFrom = scrollColOff
            val cTo = min(row.size, scrollColOff + cols + 1)

            for (c in cFrom until cTo) {
                val cell = row[c]
                val drawX = (c - scrollColOff) * cellW

                val isCursor = !cursorHidden && scrollRowOff == 0 &&
                    screenRow == cursorRow && c == cursorCol
                val isSelected = isInSelection(lineIdx, c)

                // Reverse videó feloldás, majd kurzor-invertálás, majd szelekció
                val cellFg = if (cell.reverse) cell.bg.argb else cell.fg.argb
                val cellBg = if (cell.reverse) cell.fg.argb else cell.bg.argb

                val effectiveBg = when {
                    isSelected -> SELECTION_BG
                    isCursor && cursorOn -> cellFg
                    else -> cellBg
                }
                val effectiveFg = when {
                    isCursor && cursorOn -> cellBg
                    else -> cellFg
                }

                if (effectiveBg != DEFAULT_BG || isSelected) {
                    bgPaint.color = effectiveBg
                    cellRect.set(drawX, y, drawX + cellW, y + cellH)
                    canvas.drawRect(cellRect, bgPaint)
                }

                if (cell.ch != " ") {
                    val p = if (cell.bold) boldPaint else normPaint
                    p.color = effectiveFg
                    canvas.drawText(cell.ch, drawX, y + cellBase, p)
                }

                if (cell.underline) {
                    val p = if (cell.bold) boldPaint else normPaint
                    p.color = effectiveFg
                    canvas.drawLine(drawX, y + cellH - 1f, drawX + cellW, y + cellH - 1f, p)
                }
            }
        }
    }

    // ── Publikus API ─────────────────────────────────────────────────────────

    fun append(bytes: ByteArray, length: Int) {
        buffer.write(bytes, length)
        scrollRowOff = 0
        // invalidate: a buffer ChangeListener-e intézi
    }

    fun clear() {
        buffer.clearScreen()
        // invalidate: ChangeListener
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

    private fun buildSelectedText(): String {
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
        KeyEvent.KEYCODE_DEL -> byteArrayOf(127)
        KeyEvent.KEYCODE_TAB -> byteArrayOf(9)
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(27)
        KeyEvent.KEYCODE_DPAD_UP -> cursorKeyBytes('A')
        KeyEvent.KEYCODE_DPAD_DOWN -> cursorKeyBytes('B')
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKeyBytes('C')
        KeyEvent.KEYCODE_DPAD_LEFT -> cursorKeyBytes('D')
        KeyEvent.KEYCODE_MOVE_HOME -> byteArrayOf(27, '['.code.toByte(), 'H'.code.toByte())
        KeyEvent.KEYCODE_MOVE_END -> byteArrayOf(27, '['.code.toByte(), 'F'.code.toByte())
        KeyEvent.KEYCODE_PAGE_UP -> byteArrayOf(27, '['.code.toByte(), '5'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_PAGE_DOWN -> byteArrayOf(27, '['.code.toByte(), '6'.code.toByte(), '~'.code.toByte())
        KeyEvent.KEYCODE_F1 -> fnKey("OP")
        KeyEvent.KEYCODE_F2 -> fnKey("OQ")
        KeyEvent.KEYCODE_F3 -> fnKey("OR")
        KeyEvent.KEYCODE_F4 -> fnKey("OS")
        KeyEvent.KEYCODE_F5 -> fnKey("[15~")
        KeyEvent.KEYCODE_F6 -> fnKey("[17~")
        KeyEvent.KEYCODE_F7 -> fnKey("[18~")
        KeyEvent.KEYCODE_F8 -> fnKey("[19~")
        KeyEvent.KEYCODE_F9 -> fnKey("[20~")
        KeyEvent.KEYCODE_F10 -> fnKey("[21~")
        KeyEvent.KEYCODE_F11 -> fnKey("[23~")
        KeyEvent.KEYCODE_F12 -> fnKey("[24~")
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
