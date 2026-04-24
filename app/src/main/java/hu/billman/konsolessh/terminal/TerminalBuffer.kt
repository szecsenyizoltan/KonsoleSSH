package hu.billman.konsolessh.terminal

/**
 * Platformfüggetlen terminálmodell: cellarács, scrollback, kurzor, ANSI-state.
 *
 * Az osztály NEM ismeri a Canvas-t, View-t és Context-et — ezért unit-tesztelhető
 * pure JVM-en. A renderelő oldal (TerminalView) a [lineAt], [screenLines], [snapshot]
 * metódusokon keresztül olvas, és a [setChangeListener]-en keresztül kap invalidáció-
 * jelzést minden állapotmódosítás után.
 *
 * **Fázis 5 / Lépés 2 állapota:** a váz és a read-only API él. A [write], [resize],
 * [reset] és [clearScreen] törzsek a Lépés 3–4-ben kerülnek feltöltésre; ebben a
 * commitban NOOP-ok, hogy a TerminalBuffer a TerminalView párhuzamos mirror-jaként
 * már létezhessen anélkül, hogy a UI-viselkedést bármilyen módon befolyásolná.
 */
class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val maxScrollback: Int = DEFAULT_MAX_SCROLLBACK,
) {

    // ── Dimensions ───────────────────────────────────────────────────────────

    private var _cols: Int = initialCols
    private var _rows: Int = initialRows
    val cols: Int get() = _cols
    val rows: Int get() = _rows

    // ── Screen buffers + scrollback ─────────────────────────────────────────

    private var screen: Array<TermLine> = newScreen(_cols, _rows)
    private var savedMainScreen: Array<TermLine>? = null
    private var _altScreenActive: Boolean = false
    private val scrollback: ArrayDeque<TermLine> = ArrayDeque()

    val altScreenActive: Boolean get() = _altScreenActive
    val scrollbackSize: Int get() = scrollback.size

    // ── Cursor ───────────────────────────────────────────────────────────────

    private var _cursorRow: Int = 0
    private var _cursorCol: Int = 0
    private var savedRow: Int = 0
    private var savedCol: Int = 0
    private var savedMainRow: Int = 0
    private var savedMainCol: Int = 0

    val cursorRow: Int get() = _cursorRow
    val cursorCol: Int get() = _cursorCol

    // ── Scroll region ────────────────────────────────────────────────────────

    private var scrollTop: Int = 0
    private var scrollBot: Int = _rows - 1

    // ── Terminal modes ───────────────────────────────────────────────────────

    private var _appCursorKeys: Boolean = false
    private var _cursorHidden: Boolean = false
    private var _bracketedPasteMode: Boolean = false

    val appCursorKeys: Boolean get() = _appCursorKeys
    val cursorHidden: Boolean get() = _cursorHidden
    val bracketedPasteMode: Boolean get() = _bracketedPasteMode

    // ── Current SGR style ────────────────────────────────────────────────────

    private var curFg: TermColor = TermColor.DEFAULT_FG
    private var curBg: TermColor = TermColor.DEFAULT_BG
    private var curBold: Boolean = false
    private var curUnderline: Boolean = false
    private var curReverse: Boolean = false

    // ── ANSI FSM ─────────────────────────────────────────────────────────────

    private enum class AnsiState { NORMAL, ESCAPE, CSI, OSC, DCS, CHARSET }
    private var ansiState: AnsiState = AnsiState.NORMAL
    private val csiParams: StringBuilder = StringBuilder()

    // ── Change notification ──────────────────────────────────────────────────

    fun interface ChangeListener { fun onBufferChanged() }
    private var listener: ChangeListener? = null
    fun setChangeListener(listener: ChangeListener?) { this.listener = listener }

    // ── Derived read-only state ──────────────────────────────────────────────

    /** scrollback + rows — a scrollable logikai sor-univerzum mérete. */
    val totalLines: Int get() = scrollback.size + _rows

    // ── Ingestion ────────────────────────────────────────────────────────────

    /**
     * PTY bájtok etetése. A NORMAL ág (látható karakterek, CR/LF/BS/TAB,
     * wraparound, scrollback) Lépés 3-ban élesedett; az ESCAPE / CSI / OSC /
     * DCS / CHARSET ágak ebben a commitban csendben elnyelik a következő
     * bájtot (1 karakter consume + visszatérés NORMAL-ba), funkcionálisan
     * NOOP-ként. Ezeket Lépés 4 tölti fel a teljes dispatch-csel.
     */
    fun write(bytes: ByteArray, length: Int = bytes.size) {
        processBytes(bytes, length)
        notifyChanged()
    }

    /** Konvencia: teszt-egyszerűsítő UTF-8 string-bemenet. */
    fun writeString(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    private fun processBytes(bytes: ByteArray, length: Int) {
        val text = String(bytes, 0, length, Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // Supplementary character (emoji, symbols above U+FFFF):
            // tárolás surrogate-pair stringként egyetlen cellában.
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                if (ansiState == AnsiState.NORMAL) printChar(text.substring(i, i + 2))
                i += 2
                continue
            }
            i++
            when (ansiState) {
                AnsiState.NORMAL -> when (ch) {
                    '\u001B'              -> ansiState = AnsiState.ESCAPE
                    '\r'                  -> _cursorCol = 0
                    '\n'                  -> lineFeed()
                    '\b'                  -> { if (_cursorCol > 0) _cursorCol-- }
                    '\t'                  -> {
                        _cursorCol = ((_cursorCol / 8) + 1) * 8
                        if (_cursorCol >= _cols) _cursorCol = _cols - 1
                    }
                    '\u0007'              -> { /* BEL */ }
                    '\u000E', '\u000F'    -> { /* SO/SI: ignore */ }
                    '\u009B'              -> { ansiState = AnsiState.CSI; csiParams.clear() }
                    '\u009D'              -> ansiState = AnsiState.OSC
                    '\u0090'              -> ansiState = AnsiState.DCS
                    in '\u0080'..'\u009F' -> { /* other C1: silently ignore */ }
                    else                  -> if (ch >= ' ') printChar(ch)
                }
                AnsiState.ESCAPE -> {
                    ansiState = AnsiState.NORMAL
                    when (ch) {
                        '[' -> { ansiState = AnsiState.CSI; csiParams.clear() }
                        ']' -> ansiState = AnsiState.OSC
                        'P', 'X', '^', '_' -> ansiState = AnsiState.DCS
                        'M' -> reverseIndex()
                        '7' -> { savedRow = _cursorRow; savedCol = _cursorCol }
                        '8' -> {
                            _cursorRow = savedRow.coerceIn(0, _rows - 1)
                            _cursorCol = savedCol.coerceIn(0, _cols - 1)
                        }
                        'c' -> reset()
                        '(', ')', '*', '+' -> ansiState = AnsiState.CHARSET
                        // ESC \ = ST terminator — már konszumálva, NORMAL-ban maradunk
                    }
                }
                AnsiState.CHARSET -> {
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
                    // egyéb payload-bájt csendben elnyelve
                }
            }
        }
    }

    private fun printChar(ch: Char) = printChar(ch.toString())

    private fun printChar(ch: String) {
        if (_cursorCol >= _cols) { _cursorCol = 0; lineFeed() }
        screen[_cursorRow][_cursorCol].apply {
            this.ch = ch
            fg = curFg
            bg = curBg
            bold = curBold
            underline = curUnderline
            reverse = curReverse
        }
        _cursorCol++
    }

    private fun lineFeed() {
        if (_cursorRow < scrollBot) _cursorRow++ else scrollRegionUp()
    }

    private fun scrollRegionUp() {
        if (scrollTop == 0 && !_altScreenActive) {
            scrollback.addLast(screen[0])
            if (scrollback.size > maxScrollback) scrollback.removeFirst()
        }
        for (r in scrollTop until scrollBot) screen[r] = screen[r + 1]
        screen[scrollBot] = blankRow(_cols)
    }

    private fun scrollRegionDown() {
        for (r in scrollBot downTo scrollTop + 1) screen[r] = screen[r - 1]
        screen[scrollTop] = blankRow(_cols)
    }

    private fun reverseIndex() {
        if (_cursorRow > scrollTop) _cursorRow-- else scrollRegionDown()
    }

    private fun insertLinesAtCursor(n: Int) {
        repeat(n) {
            for (r in scrollBot downTo _cursorRow + 1) screen[r] = screen[r - 1]
            screen[_cursorRow] = blankRow(_cols)
        }
    }

    private fun deleteLinesAtCursor(n: Int) {
        repeat(n) {
            for (r in _cursorRow until scrollBot) screen[r] = screen[r + 1]
            screen[scrollBot] = blankRow(_cols)
        }
    }

    // ── CSI dispatch ─────────────────────────────────────────────────────────

    private fun handleCSI(cmd: Char, params: String) {
        val isPrivate = params.startsWith("?")
        val cleaned = if (isPrivate) params.substring(1) else params
        val parts = cleaned.split(";")
        val n1 = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val n0 = parts.getOrNull(0)?.toIntOrNull() ?: 0

        when (cmd) {
            'A' -> _cursorRow = (_cursorRow - n1).coerceAtLeast(0)
            'B' -> _cursorRow = (_cursorRow + n1).coerceAtMost(_rows - 1)
            'C' -> _cursorCol = (_cursorCol + n1).coerceAtMost(_cols - 1)
            'D' -> _cursorCol = (_cursorCol - n1).coerceAtLeast(0)
            'E' -> { _cursorRow = (_cursorRow + n1).coerceAtMost(_rows - 1); _cursorCol = 0 }
            'F' -> { _cursorRow = (_cursorRow - n1).coerceAtLeast(0); _cursorCol = 0 }
            'G' -> _cursorCol = (n1 - 1).coerceIn(0, _cols - 1)
            'H', 'f' -> {
                _cursorRow = ((parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceIn(0, _rows - 1)
                _cursorCol = ((parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1).coerceIn(0, _cols - 1)
            }
            'J' -> when (n0) {
                0 -> { eraseLineEnd(); for (r in _cursorRow + 1 until _rows) screen[r] = blankRow(_cols) }
                1 -> { for (r in 0 until _cursorRow) screen[r] = blankRow(_cols); eraseLineStart() }
                2, 3 -> {
                    if (!_altScreenActive) {
                        for (r in 0.._cursorRow) scrollback.addLast(screen[r])
                        while (scrollback.size > maxScrollback) scrollback.removeFirst()
                    }
                    screen = newScreen(_cols, _rows)
                }
            }
            'K' -> when (n0) {
                0 -> eraseLineEnd()
                1 -> eraseLineStart()
                2 -> screen[_cursorRow] = blankRow(_cols)
            }
            'L' -> insertLinesAtCursor(n1)
            'M' -> deleteLinesAtCursor(n1)
            'P' -> deleteChars(n1)
            '@' -> insertBlanks(n1)
            'S' -> repeat(n1) { scrollRegionUp() }
            'T' -> repeat(n1) { scrollRegionDown() }
            'X' -> {
                val end = minOf(_cursorCol + n1, _cols)
                for (c in _cursorCol until end) screen[_cursorRow][c] = TermCell.blank()
            }
            'r' -> {
                val top = ((parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceIn(0, _rows - 1)
                val bot = ((parts.getOrNull(1)?.toIntOrNull() ?: _rows) - 1).coerceIn(0, _rows - 1)
                if (top < bot) { scrollTop = top; scrollBot = bot }
                _cursorRow = 0; _cursorCol = 0
            }
            'm' -> handleSGR(cleaned)
            'h' -> if (isPrivate) handleMode(n1, true)
            'l' -> if (isPrivate) handleMode(n1, false)
            's' -> { savedRow = _cursorRow; savedCol = _cursorCol }
            'u' -> {
                _cursorRow = savedRow.coerceIn(0, _rows - 1)
                _cursorCol = savedCol.coerceIn(0, _cols - 1)
            }
            // 'n', 't', others: silently ignore
        }
    }

    private fun handleMode(mode: Int, enable: Boolean) {
        when (mode) {
            1 -> _appCursorKeys = enable
            25 -> _cursorHidden = !enable
            47, 1049 -> if (enable) enterAltScreen() else leaveAltScreen()
            2004 -> _bracketedPasteMode = enable
        }
    }

    private fun enterAltScreen() {
        if (_altScreenActive) return
        savedMainScreen = screen
        savedMainRow = _cursorRow; savedMainCol = _cursorCol
        screen = newScreen(_cols, _rows)
        _cursorRow = 0; _cursorCol = 0
        scrollTop = 0; scrollBot = _rows - 1
        _altScreenActive = true
    }

    private fun leaveAltScreen() {
        if (!_altScreenActive) return
        screen = savedMainScreen ?: newScreen(_cols, _rows)
        savedMainScreen = null
        _altScreenActive = false
        _cursorRow = savedMainRow.coerceIn(0, _rows - 1)
        _cursorCol = 0   // prompt sorkezdő oszlopban induljon
        for (r in _cursorRow + 1 until _rows) screen[r] = blankRow(_cols)
        scrollTop = 0; scrollBot = _rows - 1
    }

    private fun eraseLineEnd() {
        for (c in _cursorCol until _cols) screen[_cursorRow][c] = TermCell.blank()
    }

    private fun eraseLineStart() {
        for (c in 0.._cursorCol) screen[_cursorRow][c] = TermCell.blank()
    }

    private fun deleteChars(n: Int) {
        val row = screen[_cursorRow]
        val shift = minOf(n, _cols - _cursorCol)
        for (c in _cursorCol until _cols - shift) row[c] = row[c + shift].copy()
        for (c in _cols - shift until _cols) row[c] = TermCell.blank()
    }

    private fun insertBlanks(n: Int) {
        val row = screen[_cursorRow]
        val shift = minOf(n, _cols - _cursorCol)
        for (c in _cols - 1 downTo _cursorCol + shift) row[c] = row[c - shift].copy()
        for (c in _cursorCol until _cursorCol + shift) row[c] = TermCell.blank()
    }

    // ── SGR ──────────────────────────────────────────────────────────────────

    private fun handleSGR(params: String) {
        if (params.isEmpty()) { resetStyle(); return }
        val parts = params.split(";")
        var i = 0
        while (i < parts.size) {
            when (val p = parts[i].toIntOrNull() ?: 0) {
                0 -> resetStyle()
                1 -> curBold = true
                2 -> { /* dim: no dedicated rendering */ }
                3 -> { /* italic: skip */ }
                4 -> curUnderline = true
                5, 6 -> { /* blink: ignore */ }
                7 -> curReverse = true
                8 -> { /* conceal: skip */ }
                9 -> { /* strikethrough: skip */ }
                21, 24 -> curUnderline = false
                22 -> curBold = false
                23 -> { /* italic off */ }
                27 -> curReverse = false
                28 -> { /* reveal */ }
                39 -> curFg = TermColor.DEFAULT_FG
                49 -> curBg = TermColor.DEFAULT_BG
                in 30..37 -> curFg = ansi16(p - 30)
                in 40..47 -> curBg = ansi16(p - 40)
                in 90..97 -> curFg = ansi16(p - 90 + 8)
                in 100..107 -> curBg = ansi16(p - 100 + 8)
                38 -> when (parts.getOrNull(i + 1)?.toIntOrNull()) {
                    5 -> { curFg = xterm256(parts.getOrNull(i + 2)?.toIntOrNull() ?: 0); i += 2 }
                    2 -> {
                        curFg = TermColor.rgb(
                            parts.getOrNull(i + 2)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 3)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 4)?.toIntOrNull() ?: 0,
                        ); i += 4
                    }
                }
                48 -> when (parts.getOrNull(i + 1)?.toIntOrNull()) {
                    5 -> { curBg = xterm256(parts.getOrNull(i + 2)?.toIntOrNull() ?: 0); i += 2 }
                    2 -> {
                        curBg = TermColor.rgb(
                            parts.getOrNull(i + 2)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 3)?.toIntOrNull() ?: 0,
                            parts.getOrNull(i + 4)?.toIntOrNull() ?: 0,
                        ); i += 4
                    }
                }
            }
            i++
        }
    }

    private fun resetStyle() {
        curFg = TermColor.DEFAULT_FG; curBg = TermColor.DEFAULT_BG
        curBold = false; curUnderline = false; curReverse = false
    }

    private fun ansi16(i: Int): TermColor {
        val c = intArrayOf(
            0x000000, 0xAA0000, 0x00AA00, 0xAA5500, 0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA,
            0x555555, 0xFF5555, 0x55FF55, 0xFFFF55, 0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF,
        )[i.coerceIn(0, 15)]
        return TermColor.rgb(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
    }

    private fun xterm256(i: Int): TermColor {
        if (i < 16) return ansi16(i)
        if (i >= 232) { val v = 8 + (i - 232) * 10; return TermColor.rgb(v, v, v) }
        val idx = i - 16
        fun comp(c: Int) = if (c == 0) 0 else c * 40 + 55
        return TermColor.rgb(comp(idx / 36), comp((idx % 36) / 6), comp(idx % 6))
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Átméretezi a screen-rácsot [newCols] × [newRows]-re. A scrollback
     * érintetlen; az alt-screen backup (savedMainScreen) eldobásra kerül,
     * mivel az méretben már nem lenne visszaállítható; a kurzor az új
     * határokba szorul. A scroll region teljes képernyőre visszaáll.
     *
     * A ChangeListener-t meghívja, így a renderer-t tájékoztatja a rácsról.
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == _cols && newRows == _rows) return
        if (newCols <= 0 || newRows <= 0) return
        val old = screen
        val oldCols = _cols
        val oldRows = _rows
        _cols = newCols
        _rows = newRows
        screen = Array(newRows) { r ->
            Array(newCols) { c ->
                if (r < oldRows && c < oldCols) old[r][c].copy() else TermCell.blank()
            }
        }
        savedMainScreen = null
        scrollTop = 0
        scrollBot = newRows - 1
        _cursorRow = _cursorRow.coerceIn(0, newRows - 1)
        _cursorCol = _cursorCol.coerceIn(0, newCols - 1)
        notifyChanged()
    }

    /** ESC c: teljes reset (állapot, scrollback, módok, SGR-stílus). */
    fun reset() {
        screen = newScreen(_cols, _rows)
        scrollback.clear()
        _cursorRow = 0; _cursorCol = 0
        savedRow = 0; savedCol = 0
        savedMainRow = 0; savedMainCol = 0
        scrollTop = 0; scrollBot = _rows - 1
        _altScreenActive = false
        savedMainScreen = null
        _appCursorKeys = false
        _cursorHidden = false
        _bracketedPasteMode = false
        resetStyle()
    }

    /**
     * A TerminalView.clear() megfelelője: csak a main screen-t üríti, scrollback
     * nem változik, a kurzor hazaugrik.
     */
    fun clearScreen() {
        screen = newScreen(_cols, _rows)
        _cursorRow = 0; _cursorCol = 0
        resetStyle()
        notifyChanged()
    }

    // ── Read-only access for renderer / selection ────────────────────────────

    /**
     * A [index] logikai sor (0..scrollbackSize-1: scrollback; utána: élő képernyő).
     * A visszaadott tömböt a hívó **nem mutálhatja**.
     */
    fun lineAt(index: Int): TermLine? {
        if (index < 0 || index >= totalLines) return null
        return if (index < scrollback.size) scrollback[index] else screen[index - scrollback.size]
    }

    fun cellAt(lineIndex: Int, col: Int): TermCell? {
        val line = lineAt(lineIndex) ?: return null
        if (col < 0 || col >= line.size) return null
        return line[col]
    }

    /** Az élő képernyő sorai ([rows] darab, egyenként [cols] cellával). */
    fun screenLines(): List<TermLine> = screen.toList()

    /** Immutable meta-állapot a renderernek. */
    fun snapshot(): TerminalSnapshot = TerminalSnapshot(
        size = TermSize(_cols, _rows),
        scrollbackSize = scrollback.size,
        cursor = CursorPos(_cursorRow, _cursorCol),
        cursorHidden = _cursorHidden,
        altScreenActive = _altScreenActive,
        appCursorKeys = _appCursorKeys,
        bracketedPasteMode = _bracketedPasteMode,
    )

    // ── Internal helpers ─────────────────────────────────────────────────────

    @Suppress("unused")
    private fun notifyChanged() {
        listener?.onBufferChanged()
    }

    companion object {
        /** Az eredeti TerminalView.MAX_SCROLLBACK érték, bit-azonos viselkedéshez. */
        const val DEFAULT_MAX_SCROLLBACK = 3000

        private fun newScreen(cols: Int, rows: Int): Array<TermLine> =
            Array(rows) { blankRow(cols) }

        private fun blankRow(cols: Int): TermLine =
            Array(cols) { TermCell.blank() }
    }
}
