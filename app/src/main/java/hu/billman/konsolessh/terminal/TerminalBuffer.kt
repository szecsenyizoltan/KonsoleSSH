package hu.billman.konsolessh.terminal

/**
 * Platformfüggetlen terminálmodell: cellarács, scrollback, kurzor, ANSI-state.
 *
 * Az osztály NEM ismeri a Canvas-t, View-t és Context-et — ezért unit-tesztelhető
 * pure JVM-en. A renderelő oldal (TerminalView) a [lineAt], [screenLines], [snapshot]
 * metódusokon keresztül olvas, és a [setChangeListener]-en keresztül kap invalidáció-
 * jelzést minden állapotmódosítás után.
 *
 * Támogatott: Unicode wcwidth szerinti cellaszélességgel (széles CJK / emoji két
 * cellán, kombináló jel az előző cellához tapad), CR/LF/BS/TAB, CSI kurzor-mozgás
 * és törlések, SGR (16 + 256 + truecolor), DECCKM (?1), cursor hide (?25),
 * alt-screen (?47 / ?1049), bracketed paste (?2004), scroll region (CSI r),
 * save/restore cursor (ESC 7/8, CSI s/u), reverse index (ESC M), G0/G1
 * karakterkészlet DEC vonalrajzolással (ESC ( 0, SO/SI), állapot- és
 * eszközlekérdezések (CSI 5n / 6n, DA1, DA2) válasza a [ResponseListener]-en,
 * OSC/DCS payload csendes konszumálása.
 *
 * Scrollback-plafon: [maxScrollback] sor (default 3000 — a korábbi TerminalView
 * értéke). Tartomány-túlfolyáskor a legrégebbi sor elvesz.
 *
 * Szálkezelés: a [write] bármely szálról hívható, és [WRITE_SLICE] méretű
 * szeletenként veszi fel a zárat, így a renderelő a [withLock] blokkban
 * nagy kimenet közben is csak rövid ideig vár. Több mezőt együtt olvasó hívó
 * (rajzolás, kijelölés) a [withLock]-ban olvasson, hogy konzisztens képet lásson.
 */
class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val maxScrollback: Int = DEFAULT_MAX_SCROLLBACK,
) {

    // ── Dimensions ───────────────────────────────────────────────────────────

    @Volatile private var _cols: Int = initialCols

    @Volatile private var _rows: Int = initialRows

    val cols: Int get() = _cols
    val rows: Int get() = _rows

    // ── Screen buffers + scrollback ─────────────────────────────────────────

    private var screen: Array<TermLine> = newScreen(_cols, _rows)
    private var savedMainScreen: Array<TermLine>? = null

    @Volatile private var _altScreenActive: Boolean = false

    private val scrollback: ArrayDeque<TermLine> = ArrayDeque()

    val altScreenActive: Boolean get() = _altScreenActive
    val scrollbackSize: Int get() = scrollback.size

    // ── Cursor ───────────────────────────────────────────────────────────────

    @Volatile private var _cursorRow: Int = 0

    @Volatile private var _cursorCol: Int = 0

    private var savedRow: Int = 0
    private var savedCol: Int = 0

    val cursorRow: Int get() = _cursorRow
    val cursorCol: Int get() = _cursorCol

    // ── Scroll region ────────────────────────────────────────────────────────

    private var scrollTop: Int = 0
    private var scrollBot: Int = _rows - 1

    // ── Terminal modes ───────────────────────────────────────────────────────

    @Volatile private var _appCursorKeys: Boolean = false

    @Volatile private var _cursorHidden: Boolean = false

    @Volatile private var _bracketedPasteMode: Boolean = false

    val appCursorKeys: Boolean get() = _appCursorKeys
    val cursorHidden: Boolean get() = _cursorHidden
    val bracketedPasteMode: Boolean get() = _bracketedPasteMode

    // ── Current SGR style ────────────────────────────────────────────────────

    private var curFg: TermColor = TermColor.DEFAULT_FG
    private var curBg: TermColor = TermColor.DEFAULT_BG
    private var curBold: Boolean = false
    private var curUnderline: Boolean = false
    private var curReverse: Boolean = false
    private var curDim: Boolean = false
    private var curItalic: Boolean = false
    private var curStrike: Boolean = false

    // ── ANSI FSM ─────────────────────────────────────────────────────────────

    private enum class AnsiState { NORMAL, ESCAPE, CSI, OSC, DCS, CHARSET }
    private var ansiState: AnsiState = AnsiState.NORMAL
    private val csiParams: StringBuilder = StringBuilder()

    /** Köztes bájtok (pl. a kurzoralak `CSI 2 SP q` szóköze); ilyen szekvenciát nem hajtunk végre. */
    private val csiIntermediates: StringBuilder = StringBuilder()

    /** Az utoljára kiírt grafikus karakter és szélessége, a REP (`CSI n b`) ismétléshez. */
    private var lastGraphic: String? = null
    private var lastGraphicWidth = 1

    private fun startCsi() {
        ansiState = AnsiState.CSI
        csiParams.clear()
        csiIntermediates.clear()
    }

    // ── Karakterkészletek (G0/G1) ────────────────────────────────────────────

    /** G0 és G1: true = DEC Special Graphics (vonalrajzolás). */
    private val decGraphics = BooleanArray(2)
    private var shiftedOut = false

    /** Melyik G-készletet jelöli ki a folyamatban lévő ESC ( / ESC ) (-1: G2/G3, elnyelve). */
    private var charsetTarget = 0

    // ── Válaszok a lekérdezésekre ────────────────────────────────────────────

    fun interface ResponseListener { fun onResponse(bytes: ByteArray) }
    private var responseListener: ResponseListener? = null
    fun setResponseListener(listener: ResponseListener?) { responseListener = listener }

    private val pendingResponse = StringBuilder()

    /** Az előző írás végén félbemaradt UTF-8 szekvencia; a következő írás elejére kerül. */
    private var utf8Carry: ByteArray = ByteArray(0)

    private val lock = Any()

    /** A [block] alatt a puffer nem változik; a renderelő ebben olvas. */
    fun <T> withLock(block: () -> T): T = synchronized(lock, block)

    // ── Change notification ──────────────────────────────────────────────────

    fun interface ChangeListener { fun onBufferChanged() }
    private var listener: ChangeListener? = null
    fun setChangeListener(listener: ChangeListener?) { this.listener = listener }

    // ── Derived read-only state ──────────────────────────────────────────────

    /** scrollback + rows — a scrollable logikai sor-univerzum mérete. */
    val totalLines: Int get() = scrollback.size + _rows

    // ── Ingestion ────────────────────────────────────────────────────────────

    /**
     * PTY-bájtok etetése: UTF-8 dekódolás, ANSI FSM, cellarács-frissítés,
     * scrollback-karbantartás. Szeletenként meghívja a ChangeListener-t
     * (a záron kívül), ha be van állítva. A darabok határán kettévágott UTF-8
     * karakter a következő hívással egészül ki.
     */
    /**
     * [respond] false: a bemenetben lévő lekérdezésekre nem megy válasz. Ez a
     * már egyszer feldolgozott kimenet visszajátszásához kell (pl. forgatás
     * után), különben a régi lekérdezések válasza bemenetként érne a shellhez.
     */
    fun write(bytes: ByteArray, length: Int = bytes.size, respond: Boolean = true) {
        var offset = 0
        do {
            val end = minOf(length, offset + WRITE_SLICE)
            val response = synchronized(lock) {
                processText(decodeUtf8(bytes, offset, end))
                takeResponse()
            }
            if (respond && response != null) responseListener?.onResponse(response)
            notifyChanged()
            offset = end
        } while (offset < length)
    }

    private fun takeResponse(): ByteArray? {
        if (pendingResponse.isEmpty()) return null
        val bytes = pendingResponse.toString().toByteArray(Charsets.UTF_8)
        pendingResponse.setLength(0)
        return bytes
    }

    /** Konvencia: teszt-egyszerűsítő UTF-8 string-bemenet. */
    fun writeString(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    private fun decodeUtf8(bytes: ByteArray, from: Int, to: Int): String {
        val data = utf8Carry + bytes.copyOfRange(from, to)
        val keep = incompleteUtf8Tail(data)
        utf8Carry = data.copyOfRange(data.size - keep, data.size)
        return String(data, 0, data.size - keep, Charsets.UTF_8)
    }

    private fun processText(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            // Supplementary character (emoji, symbols above U+FFFF): one cell
            // string holds the surrogate pair.
            if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                if (ansiState == AnsiState.NORMAL) {
                    printText(text.substring(i, i + 2), CharWidth.of(text.codePointAt(i)))
                }
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
                        _cursorCol = ((_cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH
                        if (_cursorCol >= _cols) _cursorCol = _cols - 1
                    }
                    '\u0007'              -> { /* BEL */ }
                    '\u000E'              -> shiftedOut = true    // SO: G1
                    '\u000F'              -> shiftedOut = false   // SI: G0
                    '\u009B'              -> startCsi()
                    '\u009D'              -> ansiState = AnsiState.OSC
                    '\u0090'              -> ansiState = AnsiState.DCS
                    in '\u0080'..'\u009F' -> { /* other C1: silently ignore */ }
                    else                  -> if (ch >= ' ') printChar(ch)
                }
                AnsiState.ESCAPE -> {
                    ansiState = AnsiState.NORMAL
                    when (ch) {
                        '[' -> startCsi()
                        ']' -> ansiState = AnsiState.OSC
                        'P', 'X', '^', '_' -> ansiState = AnsiState.DCS
                        'M' -> reverseIndex()
                        '7' -> saveCursor()
                        '8' -> restoreCursor()
                        'c' -> reset()
                        '(', ')', '*', '+' -> {
                            charsetTarget = when (ch) {
                                '(' -> 0
                                ')' -> 1
                                else -> -1
                            }
                            ansiState = AnsiState.CHARSET
                        }
                        // ESC \ = ST terminator — már konszumálva, NORMAL-ban maradunk
                    }
                }
                AnsiState.CHARSET -> {
                    if (charsetTarget >= 0) decGraphics[charsetTarget] = ch == '0'
                    ansiState = AnsiState.NORMAL
                }
                // ECMA-48: parameter bytes 0x30–0x3F, intermediate bytes
                // 0x20–0x2F, then one final byte 0x40–0x7E.
                AnsiState.CSI -> when (ch) {
                    in '0'..'?' -> csiParams.append(ch)
                    in ' '..'/' -> csiIntermediates.append(ch)
                    in '@'..'~' -> {
                        if (csiIntermediates.isEmpty()) handleCSI(ch, csiParams.toString())
                        ansiState = AnsiState.NORMAL
                    }
                    '\u001B' -> ansiState = AnsiState.ESCAPE
                    else -> { /* other controls inside a sequence: ignored */ }
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

    private fun printChar(ch: Char) {
        val mapped = if (decGraphics[if (shiftedOut) 1 else 0]) DecGraphics.map(ch) else ch
        printText(mapped.toString(), CharWidth.of(mapped.code))
    }

    /**
     * Egy karakter a kurzorhoz. [width] 0: az előző cellához fűződik
     * (kombináló jel); 2: a cella mellé egy üres folytatócella ([WIDE_TAIL])
     * kerül, és ha a sor végén már nem fér el, új sorba tördel.
     */
    private fun printText(ch: String, width: Int) {
        if (width == 0) {
            appendToPreviousCell(ch)
            return
        }
        if (_cursorCol + width > _cols) {
            if (width == 2 && _cursorCol < _cols) putCell(_cursorRow, _cursorCol, " ")
            _cursorCol = 0
            lineFeed()
        }
        putCell(_cursorRow, _cursorCol, ch)
        if (width == 2) putCell(_cursorRow, _cursorCol + 1, WIDE_TAIL)
        _cursorCol += width
        lastGraphic = ch
        lastGraphicWidth = width
    }

    private fun appendToPreviousCell(mark: String) {
        var col = _cursorCol - 1
        if (col < 0) return
        val row = screen[_cursorRow]
        if (row[col].ch == WIDE_TAIL && col > 0) col--
        row[col].ch += mark
    }

    // Overwriting half of a wide character must clear its other half.
    private fun putCell(r: Int, c: Int, ch: String) {
        val row = screen[r]
        if (row[c].ch == WIDE_TAIL && c > 0 && ch != WIDE_TAIL) row[c - 1].ch = " "
        if (ch != WIDE_TAIL && c + 1 < _cols && row[c + 1].ch == WIDE_TAIL) row[c + 1].ch = " "
        row[c].apply {
            this.ch = ch
            fg = curFg
            bg = curBg
            bold = curBold
            underline = curUnderline
            reverse = curReverse
            dim = curDim
            italic = curItalic
            strikethrough = curStrike
        }
    }

    private fun reportStatus(params: String) {
        when (params) {
            "5" -> pendingResponse.append("\u001B[0n")
            "6" -> pendingResponse.append("\u001B[${_cursorRow + 1};${minOf(_cursorCol, _cols - 1) + 1}R")
            "?6" -> pendingResponse.append("\u001B[?${_cursorRow + 1};${minOf(_cursorCol, _cols - 1) + 1}R")
        }
    }

    private fun reportDeviceAttributes(params: String) {
        when (params) {
            "", "0" -> pendingResponse.append(PRIMARY_DA)
            ">", ">0" -> pendingResponse.append(SECONDARY_DA)
        }
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
        screen[scrollBot] = blankLine()
    }

    private fun scrollRegionDown() {
        for (r in scrollBot downTo scrollTop + 1) screen[r] = screen[r - 1]
        screen[scrollTop] = blankLine()
    }

    // Back colour erase (the xterm-256color terminfo has bce): erased and
    // scrolled-in cells take the current background, nothing else.
    private fun blankCell() = TermCell(bg = curBg)

    private fun blankLine(): TermLine = Array(_cols) { blankCell() }

    private fun reverseIndex() {
        if (_cursorRow > scrollTop) _cursorRow-- else scrollRegionDown()
    }

    private fun insertLinesAtCursor(n: Int) {
        repeat(n) {
            for (r in scrollBot downTo _cursorRow + 1) screen[r] = screen[r - 1]
            screen[_cursorRow] = blankLine()
        }
    }

    private fun deleteLinesAtCursor(n: Int) {
        repeat(n) {
            for (r in _cursorRow until scrollBot) screen[r] = screen[r + 1]
            screen[scrollBot] = blankLine()
        }
    }

    // ── CSI dispatch ─────────────────────────────────────────────────────────

    private fun handleCSI(cmd: Char, params: String) {
        val isPrivate = params.startsWith("?")
        val cleaned = if (isPrivate) params.substring(1) else params
        val parts = cleaned.split(";")
        // A zero count means the default of one, as in xterm.
        val n1 = (parts.getOrNull(0)?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val n0 = parts.getOrNull(0)?.toIntOrNull() ?: 0

        when (cmd) {
            'A' -> _cursorRow = (_cursorRow - n1).coerceAtLeast(0)
            'B' -> _cursorRow = (_cursorRow + n1).coerceAtMost(_rows - 1)
            'C' -> _cursorCol = (_cursorCol + n1).coerceAtMost(_cols - 1)
            'D' -> _cursorCol = (_cursorCol - n1).coerceAtLeast(0)
            'E' -> { _cursorRow = (_cursorRow + n1).coerceAtMost(_rows - 1); _cursorCol = 0 }
            'F' -> { _cursorRow = (_cursorRow - n1).coerceAtLeast(0); _cursorCol = 0 }
            'G', '`' -> _cursorCol = (n1 - 1).coerceIn(0, _cols - 1)
            'a' -> _cursorCol = (_cursorCol + n1).coerceAtMost(_cols - 1)
            'd' -> _cursorRow = (n1 - 1).coerceIn(0, _rows - 1)
            'e' -> _cursorRow = (_cursorRow + n1).coerceAtMost(_rows - 1)
            'I' -> repeat(n1) { _cursorCol = minOf(_cols - 1, (_cursorCol / TAB_WIDTH + 1) * TAB_WIDTH) }
            'Z' -> repeat(n1) { _cursorCol = maxOf(0, (_cursorCol - 1) / TAB_WIDTH * TAB_WIDTH) }
            'b' -> lastGraphic?.let { ch -> repeat(n1) { printText(ch, lastGraphicWidth) } }
            'H', 'f' -> {
                _cursorRow = ((parts.getOrNull(0)?.toIntOrNull() ?: 1) - 1).coerceIn(0, _rows - 1)
                _cursorCol = ((parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1).coerceIn(0, _cols - 1)
            }
            'J' -> when (n0) {
                0 -> { eraseLineEnd(); for (r in _cursorRow + 1 until _rows) screen[r] = blankLine() }
                1 -> { for (r in 0 until _cursorRow) screen[r] = blankLine(); eraseLineStart() }
                2, 3 -> {
                    if (!_altScreenActive) {
                        for (r in 0.._cursorRow) scrollback.addLast(screen[r])
                        while (scrollback.size > maxScrollback) scrollback.removeFirst()
                    }
                    screen = Array(_rows) { blankLine() }
                }
            }
            'K' -> when (n0) {
                0 -> eraseLineEnd()
                1 -> eraseLineStart()
                2 -> screen[_cursorRow] = blankLine()
            }
            'L' -> insertLinesAtCursor(n1)
            'M' -> deleteLinesAtCursor(n1)
            'P' -> deleteChars(n1)
            '@' -> insertBlanks(n1)
            'S' -> repeat(n1) { scrollRegionUp() }
            'T' -> repeat(n1) { scrollRegionDown() }
            'X' -> {
                val end = minOf(_cursorCol + n1, _cols)
                for (c in _cursorCol until end) screen[_cursorRow][c] = blankCell()
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
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> reportStatus(params)
            'c' -> reportDeviceAttributes(params)
            // 't', others: silently ignore
        }
    }

    private fun handleMode(mode: Int, enable: Boolean) {
        when (mode) {
            1 -> _appCursorKeys = enable
            25 -> _cursorHidden = !enable
            // As in xterm: 47 and 1047 only switch buffers, 1048 saves and
            // restores the cursor, 1049 does both around the switch.
            47, 1047 -> if (enable) enterAltScreen() else leaveAltScreen(restoreCursor = false)
            1048 -> if (enable) saveCursor() else restoreCursor()
            1049 -> if (enable) {
                saveCursor()
                enterAltScreen()
            } else {
                leaveAltScreen(restoreCursor = true)
            }
            2004 -> _bracketedPasteMode = enable
        }
    }

    private fun enterAltScreen() {
        if (_altScreenActive) return
        savedMainScreen = screen
        screen = newScreen(_cols, _rows)
        scrollTop = 0; scrollBot = _rows - 1
        _altScreenActive = true
    }

    private fun leaveAltScreen(restoreCursor: Boolean) {
        if (!_altScreenActive) return
        screen = savedMainScreen ?: newScreen(_cols, _rows)
        savedMainScreen = null
        _altScreenActive = false
        if (restoreCursor) restoreCursor()
        scrollTop = 0; scrollBot = _rows - 1
    }

    // DECSC / DECRC (ESC 7 / 8, CSI ?1048 h/l, and the cursor half of ?1049).
    private fun saveCursor() {
        savedRow = _cursorRow; savedCol = _cursorCol
    }

    private fun restoreCursor() {
        _cursorRow = savedRow.coerceIn(0, _rows - 1)
        _cursorCol = savedCol.coerceIn(0, _cols - 1)
    }

    private fun eraseLineEnd() {
        for (c in _cursorCol until _cols) screen[_cursorRow][c] = blankCell()
    }

    private fun eraseLineStart() {
        for (c in 0.._cursorCol) screen[_cursorRow][c] = blankCell()
    }

    private fun deleteChars(n: Int) {
        val row = screen[_cursorRow]
        val shift = minOf(n, _cols - _cursorCol)
        for (c in _cursorCol until _cols - shift) row[c] = row[c + shift].copy()
        for (c in _cols - shift until _cols) row[c] = blankCell()
    }

    private fun insertBlanks(n: Int) {
        val row = screen[_cursorRow]
        val shift = minOf(n, _cols - _cursorCol)
        for (c in _cols - 1 downTo _cursorCol + shift) row[c] = row[c - shift].copy()
        for (c in _cursorCol until _cursorCol + shift) row[c] = blankCell()
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
                2 -> curDim = true
                3 -> curItalic = true
                4 -> curUnderline = true
                5, 6 -> { /* blink: ignore */ }
                7 -> curReverse = true
                8 -> { /* conceal: skip */ }
                9 -> curStrike = true
                // 21 is double underline in ECMA-48 / current xterm; drawn single.
                21 -> curUnderline = true
                24 -> curUnderline = false
                22 -> {
                    curBold = false
                    curDim = false
                }
                23 -> curItalic = false
                29 -> curStrike = false
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
        curDim = false; curItalic = false; curStrike = false
    }

    // SGR-paletta: SgrPalette.
    private fun ansi16(i: Int): TermColor = SgrPalette.ansi16(i)
    private fun xterm256(i: Int): TermColor = SgrPalette.xterm256(i)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Átméretezi a screen-rácsot [newCols] × [newRows]-re. Ha a képernyő
     * alacsonyabb lesz, a kurzor feletti sorok tolódnak ki (a fő képernyőn a
     * görgetési előzménybe), így a kurzor sora megmarad. Az alt-screen mögötti
     * fő képernyő is átméreteződik, nem vész el. A scroll region teljes
     * képernyőre visszaáll.
     *
     * A ChangeListener-t meghívja, így a renderer-t tájékoztatja a rácsról.
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        val changed = synchronized(lock) { resizeLocked(newCols, newRows) }
        if (changed) notifyChanged()
    }

    private fun resizeLocked(newCols: Int, newRows: Int): Boolean {
        if (newCols == _cols && newRows == _rows) return false
        // When the screen gets shorter, the lines above the cursor give way
        // (into the scrollback on the main screen), so the cursor line stays.
        val shift = maxOf(0, _cursorRow - (newRows - 1))
        if (shift > 0 && !_altScreenActive) {
            for (r in 0 until shift) scrollback.addLast(screen[r])
            while (scrollback.size > maxScrollback) scrollback.removeFirst()
        }
        screen = resizeGrid(screen, shift, newCols, newRows)
        // The main screen behind vim/less keeps its content across a rotation.
        savedMainScreen = savedMainScreen?.let { resizeGrid(it, 0, newCols, newRows) }
        _cols = newCols
        _rows = newRows
        scrollTop = 0
        scrollBot = newRows - 1
        _cursorRow = (_cursorRow - shift).coerceIn(0, newRows - 1)
        _cursorCol = _cursorCol.coerceIn(0, newCols - 1)
        savedRow = savedRow.coerceIn(0, newRows - 1)
        savedCol = savedCol.coerceIn(0, newCols - 1)
        return true
    }

    /** ESC c: teljes reset (állapot, scrollback, módok, SGR-stílus). */
    fun reset() = synchronized(lock) {
        screen = newScreen(_cols, _rows)
        utf8Carry = ByteArray(0)
        scrollback.clear()
        _cursorRow = 0; _cursorCol = 0
        savedRow = 0; savedCol = 0
        scrollTop = 0; scrollBot = _rows - 1
        _altScreenActive = false
        savedMainScreen = null
        _appCursorKeys = false
        _cursorHidden = false
        _bracketedPasteMode = false
        decGraphics.fill(false)
        shiftedOut = false
        resetStyle()
    }

    /**
     * A TerminalView.clear() megfelelője: csak a main screen-t üríti, scrollback
     * nem változik, a kurzor hazaugrik.
     */
    fun clearScreen() {
        synchronized(lock) {
            screen = newScreen(_cols, _rows)
            _cursorRow = 0; _cursorCol = 0
            resetStyle()
        }
        notifyChanged()
    }

    // ── Read-only access for renderer / selection ────────────────────────────

    /**
     * A [index] logikai sor (0..scrollbackSize-1: scrollback; utána: élő képernyő).
     * A visszaadott tömböt a hívó **nem mutálhatja**.
     */
    fun lineAt(index: Int): TermLine? = synchronized(lock) {
        if (index < 0 || index >= totalLines) return null
        if (index < scrollback.size) scrollback[index] else screen[index - scrollback.size]
    }

    fun cellAt(lineIndex: Int, col: Int): TermCell? {
        val line = lineAt(lineIndex) ?: return null
        if (col < 0 || col >= line.size) return null
        return line[col]
    }

    /** Az élő képernyő sorai ([rows] darab, egyenként [cols] cellával). */
    fun screenLines(): List<TermLine> = synchronized(lock) { screen.toList() }

    /** Immutable meta-állapot a renderernek. */
    fun snapshot(): TerminalSnapshot = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked() = TerminalSnapshot(
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

        /** A széles karakter második cellája: üres, a rajzoló és a kijelölés kihagyja. */
        const val WIDE_TAIL = ""

        /** DA1: VT220, ANSI-színekkel (mint sok xterm-kompatibilis terminál). */
        private const val PRIMARY_DA = "\u001B[?62;22c"

        /** DA2: típus 1 (VT220), verzió 0. */
        private const val SECONDARY_DA = "\u001B[>1;0;0c"

        private const val TAB_WIDTH = 8

        /** Ennyi bájtot dolgoz fel egy zárfelvétel alatt a [write]. */
        const val WRITE_SLICE = 4096

        /**
         * A tömb végén álló, még nem teljes UTF-8 szekvencia hossza (0..3).
         * Hibás bájtsornál 0: azt a dekóder cserekarakterre fordítja.
         */
        internal fun incompleteUtf8Tail(data: ByteArray): Int {
            for (back in 1..minOf(3, data.size)) {
                val b = data[data.size - back].toInt() and 0xFF
                if (b and 0xC0 != 0x80) {
                    val need = when {
                        b >= 0xF0 -> 4
                        b >= 0xE0 -> 3
                        b >= 0xC0 -> 2
                        else -> 1
                    }
                    return if (need > back) back else 0
                }
            }
            return 0
        }

        private fun resizeGrid(old: Array<TermLine>, shift: Int, cols: Int, rows: Int): Array<TermLine> =
            Array(rows) { r ->
                val src = old.getOrNull(r + shift)
                Array(cols) { c -> src?.getOrNull(c)?.copy() ?: TermCell.blank() }
            }

        private fun newScreen(cols: Int, rows: Int): Array<TermLine> =
            Array(rows) { blankRow(cols) }

        private fun blankRow(cols: Int): TermLine =
            Array(cols) { TermCell.blank() }
    }
}
