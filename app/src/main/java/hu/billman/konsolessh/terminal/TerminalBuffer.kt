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

    // ── Ingestion (STUB — Lépés 3–4 tölti) ───────────────────────────────────

    /**
     * PTY bájtok etetése. Lépés 3–4-ben UTF-8 dekódolás + ANSI FSM.
     * Jelenleg NOOP.
     */
    fun write(bytes: ByteArray, length: Int = bytes.size) {
        // Lépés 3: NORMAL ág (CR, LF, BS, TAB, printChar)
        // Lépés 4: ESCAPE / CSI / OSC / DCS / CHARSET ágak
    }

    /** Konvencia: teszt-egyszerűsítő UTF-8 string-bemenet. */
    fun writeString(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
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
            Array(rows) { Array(cols) { TermCell.blank() } }
    }
}
