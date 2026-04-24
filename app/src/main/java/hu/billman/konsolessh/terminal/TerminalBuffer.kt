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
                    // Lépés 4 előtt: a bevezető karakterek alapján átlépünk a megfelelő
                    // alállapotba (CSI, OSC, DCS, CHARSET), ahol a payload-bájtokat
                    // csendben elnyeljük. Egyéb ESC parancsok (M, 7, 8, c) NOOP-ok.
                    when (ch) {
                        '[' -> { ansiState = AnsiState.CSI; csiParams.clear() }
                        ']' -> ansiState = AnsiState.OSC
                        'P', 'X', '^', '_' -> ansiState = AnsiState.DCS
                        '(', ')', '*', '+' -> ansiState = AnsiState.CHARSET
                        else -> ansiState = AnsiState.NORMAL
                    }
                }
                AnsiState.CHARSET -> {
                    // Consume charset designator byte ('B' = ASCII, '0' = graphics, stb.)
                    ansiState = AnsiState.NORMAL
                }
                AnsiState.CSI -> {
                    if (ch.isDigit() || ch == ';' || ch == '?' ||
                        ch == '>' || ch == '<' || ch == '!' || ch == '=' || ch == '$') {
                        csiParams.append(ch)
                    } else {
                        // Lépés 4-ig a CSI-dispatch nincs implementálva; a bájtot silent consume.
                        csiParams.clear()
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

    @Suppress("unused")
    private fun scrollRegionDown() {
        for (r in scrollBot downTo scrollTop + 1) screen[r] = screen[r - 1]
        screen[scrollTop] = blankRow(_cols)
    }

    // ── Lifecycle (STUB — Lépés 4–5 tölti) ───────────────────────────────────

    fun resize(newCols: Int, newRows: Int) {
        // Lépés 5: screen/scrollback átméretezése, kurzor coerce, alt-backup drop
    }

    /** ESC c teljes reset. */
    fun reset() {
        // Lépés 4
    }

    /** Main screen törlése (a TerminalView.clear() megfelelője). */
    fun clearScreen() {
        // Lépés 4
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
