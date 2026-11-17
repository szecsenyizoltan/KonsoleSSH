package hu.billman.konsolessh.terminal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.PersistableBundle
import android.text.InputType
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
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
 * The [TerminalBuffer] holds the terminal's whole logical state (cell grid,
 * scrollback, cursor, ANSI state, SGR style, modes). This class draws it
 * and handles input (touch, keys, IME), view scrolling, text selection with
 * the clipboard, and the blinking cursor.
 *
 * The buffer's ChangeListener requests every redraw, so none can be
 * forgotten. [append] may be called from any thread: the bytes are
 * processed on the caller's thread, and drawing reads under the buffer's
 * lock.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onKeyInput: ((ByteArray) -> Unit)? = null
    var onTerminalResize: ((cols: Int, rows: Int) -> Unit)? = null

    /** The terminal's answer to a query (e.g. the cursor position); may come from a background thread. */
    var onTerminalResponse: ((ByteArray) -> Unit)? = null
    var horizontalScrollEnabled = true
    val fontSize: Float get() = fontSizeSp

    val currentCols get() = buffer.cols
    val currentRows get() = buffer.rows

    companion object {
        private const val MIN_COLS = 80
        private const val LONG_PRESS_MS = 400L
        private const val CURSOR_ON_MS = 600L
        private const val CURSOR_OFF_MS = 300L
        private const val MIN_FONT_SP = 6f
        private const val MAX_FONT_SP = 40f
        private const val ID_COPY = 1
        private const val ID_PASTE = 2
    }

    // ── Logical state: all of it in the buffer ────────────────────────────────

    internal val buffer = TerminalBuffer(initialCols = MIN_COLS, initialRows = 24)

    // ── View metrics ─────────────────────────────────────────────────────────

    private var viewW = 0
    private var viewH = 0

    // ── Font & painting ──────────────────────────────────────────────────────

    private var fontSizeSp = 12f
    private var firstLayout = true
    private val renderer = TerminalRenderer(loadTypeface(context))
    private val cellW get() = renderer.cellWidth
    private val cellH get() = renderer.cellHeight

    // ── View scrolling ───────────────────────────────────────────────────────

    private var scrollRowOff = 0

    /** New output arrived: the next draw scrolls to the bottom. */
    @Volatile private var followOutput = false
    private var scrollColOff = 0
    private var touchScrollFrac = 0f

    // ── Text selection ───────────────────────────────────────────────────────

    private val selection = TerminalSelection()
    private var selActionMode: ActionMode? = null

    private var lpX = 0f
    private var lpY = 0f
    private val longPressRunnable = Runnable {
        selectionFromThisPress = true
        startSelectionAt(lpX, lpY)
    }

    /** The current touch started the selection, so lifting the finger keeps it. */
    private var selectionFromThisPress = false

    // ── Cursor blinking ──────────────────────────────────────────────────────

    private var cursorOn = true
    private val blinkTask = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, if (cursorOn) CURSOR_ON_MS else CURSOR_OFF_MS)
        }
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    private var touchX0 = 0f
    private var touchY0 = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var touchMoved = false

    /**
     * Whether the current gesture's movement is this view's: a vertical scroll,
     * its own horizontal scroll, or a selection. Until the gesture is classified,
     * and for a horizontal swipe it leaves to its parent (a pager), move events
     * are reported unhandled, so a Compose pager can take the swipe.
     */
    private var ownsGesture = false

    init {
        setBackgroundColor(TerminalRenderer.BACKGROUND)
        isFocusable = true
        isFocusableInTouchMode = true
        applyFontMetrics()
        // The buffer reports every change, possibly from a background thread;
        // postInvalidateOnAnimation is thread-safe and draws once per frame.
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
        renderer.setTextSize(FontScaling.spToPx(fontSizeSp, resources.displayMetrics))
    }

    /**
     * The index of the logical line shown on visual row 0. Content sticks to
     * the bottom of the view while there is no scrollback and the user has
     * not scrolled; otherwise the scrollback is scrolled through.
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

    // ── Sizing ───────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        viewW = w
        viewH = h
        if (firstLayout) {
            firstLayout = false
            val wantedCellW = w.toFloat() / MIN_COLS
            val ratio = cellW / renderer.textSize
            fontSizeSp = FontScaling.pxToSp(wantedCellW / ratio, resources.displayMetrics)
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

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(TerminalRenderer.BACKGROUND)
        if (followOutput) {
            followOutput = false
            scrollRowOff = 0
        }
        buffer.withLock {
            renderer.draw(
                canvas,
                buffer,
                firstLine = computeFirstLine(),
                firstCol = scrollColOff,
                showCursor = !buffer.cursorHidden && scrollRowOff == 0 && cursorOn,
                selection = if (selection.active) selection.bounds() else null,
            )
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Callable from any thread; large output is best fed from a background
     * thread. [respond] false marks replayed output, whose queries get no
     * answer (see [TerminalBuffer.write]).
     */
    fun append(bytes: ByteArray, length: Int, respond: Boolean = true) {
        followOutput = true
        buffer.write(bytes, length, respond)
        // the buffer's ChangeListener requests the redraw
    }

    fun clear() {
        buffer.clearScreen()
        // invalidate: ChangeListener
    }

    /** Clears everything, the scrollback too, for static content that is redrawn. */
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

    /** Requests focus only; does not open the soft keyboard. */
    fun focusInput() {
        post { requestFocus() }
    }

    /** Requests focus and opens the soft keyboard, for an explicit user action. */
    fun focusAndShowKeyboard() {
        requestFocus()
        val imm = context.getSystemService<InputMethodManager>() ?: return
        if (!imm.showSoftInput(this, 0)) {
            post { imm.showSoftInput(this, 0) }
        }
    }

    fun cursorKeyBytes(dir: Char): ByteArray = KeyEncoder.cursorKey(dir, buffer.appCursorKeys)

    // ── Text selection ───────────────────────────────────────────────────────

    private fun touchToLineCol(x: Float, y: Float): Pair<Int, Int> {
        val visualRow = (y / cellH).toInt().coerceIn(0, buffer.rows - 1)
        val col = ((x / cellW).toInt() + scrollColOff).coerceIn(0, buffer.cols - 1)
        return Pair(computeFirstLine() + visualRow, col)
    }

    private fun startSelectionAt(x: Float, y: Float) {
        val (line, col) = touchToLineCol(x, y)
        selection.start(line, col)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
        selActionMode?.finish()
        selActionMode = startActionMode(selCallback, ActionMode.TYPE_FLOATING)
    }

    private fun clearSelection() {
        selection.clear()
        invalidate()
    }

    private fun buildSelectedText(): String = buffer.withLock { selection.text(buffer.cols, buffer::lineAt) }

    private fun copyToClipboard() {
        val text = buildSelectedText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = ClipData.newPlainText("terminal", text)
            // Text copied from a terminal is often a token or a password, so the
            // system is told not to preview it. Below API 33 the documented key name is needed.
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
        onKeyInput?.invoke(KeyEncoder.text(text, buffer.bracketedPasteMode))
    }

    fun getSelectedText(): String = buildSelectedText()

    private val selCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, ID_COPY, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(0, ID_PASTE, 1, android.R.string.paste)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                ID_COPY -> {
                    copyToClipboard()
                    mode.finish()
                    return true
                }
                ID_PASTE -> {
                    pasteFromClipboard()
                    mode.finish()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selActionMode = null
            clearSelection()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val fl = computeFirstLine()
            val r1 = (selection.startLine - fl).coerceIn(0, buffer.rows - 1)
            val r2 = (selection.endLine - fl).coerceIn(0, buffer.rows - 1)
            outRect.set(
                0,
                (min(r1, r2) * cellH).toInt(),
                width,
                ((max(r1, r2) + 1) * cellH).toInt(),
            )
        }
    }

    // ── Touch ────────────────────────────────────────────────────────────────

    /**
     * Decides, once the finger has moved past the slop, whose the gesture is.
     * A vertical drag and a horizontal one this view can still scroll are its
     * own; a horizontal swipe with no horizontal scrolling, or at the edge it
     * pushes against, is left to the parent pager.
     */
    private fun claimGestureIfOwn(dxTotal: Float, dyTotal: Float) {
        val isHoriz = abs(dxTotal) > abs(dyTotal)
        if (isHoriz && !horizontalScrollEnabled) return
        val maxColOff = max(0, buffer.cols - (viewW / cellW).toInt())
        val atEdge = isHoriz && ((dxTotal > 0 && scrollColOff == 0) || (dxTotal < 0 && scrollColOff >= maxColOff))
        if (!atEdge) {
            ownsGesture = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                touchX0 = ev.x
                touchY0 = ev.y
                touchX = ev.x
                touchY = ev.y
                touchMoved = false
                touchScrollFrac = 0f
                ownsGesture = false
                selectionFromThisPress = false
                lpX = ev.x
                lpY = ev.y
                postDelayed(longPressRunnable, LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - touchX
                val dy = ev.y - touchY
                if (!touchMoved && (abs(ev.x - touchX0) > 8f || abs(ev.y - touchY0) > 8f)) {
                    touchMoved = true
                    removeCallbacks(longPressRunnable)
                    if (!selection.active) claimGestureIfOwn(ev.x - touchX0, ev.y - touchY0)
                }
                if (selection.active) {
                    ownsGesture = true
                    val (line, col) = touchToLineCol(ev.x, ev.y)
                    selection.extendTo(line, col)
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
                    touchX = ev.x
                    touchY = ev.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                when {
                    selection.active && !touchMoved && !selectionFromThisPress -> selActionMode?.finish()
                    !touchMoved && !selection.active -> performClick()
                }
            }
        }
        // The down and the up are always this view's; a move only once the gesture is.
        return ev.action != MotionEvent.ACTION_MOVE || ownsGesture
    }

    // A tap opens the keyboard; going through performClick lets accessibility
    // services trigger the same action.
    override fun performClick(): Boolean {
        super.performClick()
        focusAndShowKeyboard()
        return true
    }

    // ── Key dispatch ─────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isShiftPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        scrollRowOff = (scrollRowOff + buffer.rows).coerceAtMost(buffer.scrollbackSize)
                        invalidate()
                        return true
                    }
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        scrollRowOff = (scrollRowOff - buffer.rows).coerceAtLeast(0)
                        invalidate()
                        return true
                    }
                }
            }
            val bytes = keyBytes(event)
            if (bytes != null) {
                onKeyInput?.invoke(bytes)
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.isShiftPressed && event.keyCode in
                listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
            ) {
                return true
            }
            if (keyBytes(event) != null) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blinkTask)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinkTask)
        removeCallbacks(longPressRunnable)
        selActionMode?.finish()
    }

    // ── IME ──────────────────────────────────────────────────────────────────

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection(
            view = this,
            send = { bytes -> onKeyInput?.invoke(bytes) },
            bracketedPaste = { buffer.bracketedPasteMode },
            keyBytes = ::keyBytes,
        ).also { it.resetToSentinel() }
    }

    private fun keyBytes(event: KeyEvent): ByteArray? =
        KeyEncoder.key(event.keyCode, event.unicodeChar, buffer.appCursorKeys)
}
