package hu.billman.konsolessh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fázis 5 / Lépés 2: váz-szintű tesztek a TerminalBuffer-hez.
 * A write/resize/reset/clearScreen még NOOP — azokra a tesztek Lépés 3–4-ben
 * érkeznek. Itt a kezdeti állapot, a read-only API és a ChangeListener
 * kontraktusa ellenőrzendő.
 */
class TerminalBufferTest {

    @Test
    fun `default constructor produces 80 by 24 empty buffer`() {
        val buf = TerminalBuffer()
        assertEquals(80, buf.cols)
        assertEquals(24, buf.rows)
        assertEquals(0, buf.scrollbackSize)
        assertEquals(24, buf.totalLines)
        assertEquals(0, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `custom constructor honours initial dimensions`() {
        val buf = TerminalBuffer(initialCols = 120, initialRows = 40)
        assertEquals(120, buf.cols)
        assertEquals(40, buf.rows)
        assertEquals(40, buf.totalLines)
    }

    @Test
    fun `maxScrollback defaults to 3000 matching TerminalView`() {
        val buf = TerminalBuffer()
        assertEquals(3000, buf.maxScrollback)
    }

    @Test
    fun `maxScrollback is configurable for tests`() {
        val buf = TerminalBuffer(maxScrollback = 10)
        assertEquals(10, buf.maxScrollback)
    }

    @Test
    fun `lineAt zero returns a row of blank cells sized to cols`() {
        val buf = TerminalBuffer(initialCols = 40, initialRows = 10)
        val line = buf.lineAt(0)
        assertNotNull(line)
        assertEquals(40, line!!.size)
        line.forEach { cell ->
            assertEquals(" ", cell.ch)
            assertEquals(TermColor.DEFAULT_FG, cell.fg)
            assertEquals(TermColor.DEFAULT_BG, cell.bg)
            assertFalse(cell.bold)
        }
    }

    @Test
    fun `lineAt out of range returns null`() {
        val buf = TerminalBuffer()
        assertNull(buf.lineAt(-1))
        assertNull(buf.lineAt(buf.totalLines))
        assertNull(buf.lineAt(9999))
    }

    @Test
    fun `cellAt returns the cell instance from the backing line`() {
        val buf = TerminalBuffer(initialCols = 5, initialRows = 3)
        val cell = buf.cellAt(0, 2)
        assertNotNull(cell)
        val line = buf.lineAt(0)!!
        // Same object identity — verifies lineAt and cellAt share the backing array
        assertSame(line[2], cell)
    }

    @Test
    fun `cellAt returns null for out of range col`() {
        val buf = TerminalBuffer(initialCols = 5, initialRows = 3)
        assertNull(buf.cellAt(0, -1))
        assertNull(buf.cellAt(0, 5))
    }

    @Test
    fun `cellAt returns null for out of range lineIndex`() {
        val buf = TerminalBuffer()
        assertNull(buf.cellAt(-1, 0))
        assertNull(buf.cellAt(buf.totalLines, 0))
    }

    @Test
    fun `screenLines returns rows number of lines`() {
        val buf = TerminalBuffer(initialCols = 10, initialRows = 7)
        val lines = buf.screenLines()
        assertEquals(7, lines.size)
        lines.forEach { assertEquals(10, it.size) }
    }

    @Test
    fun `snapshot reflects initial state`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 24)
        val snap = buf.snapshot()
        assertEquals(80, snap.size.cols)
        assertEquals(24, snap.size.rows)
        assertEquals(0, snap.scrollbackSize)
        assertEquals(0, snap.cursor.row)
        assertEquals(0, snap.cursor.col)
        assertFalse(snap.cursorHidden)
        assertFalse(snap.altScreenActive)
        assertFalse(snap.appCursorKeys)
        assertFalse(snap.bracketedPasteMode)
    }

    @Test
    fun `ChangeListener not invoked without any write`() {
        val buf = TerminalBuffer()
        var count = 0
        buf.setChangeListener { count++ }
        // No write yet → no notification
        assertEquals(0, count)
    }

    @Test
    fun `ChangeListener can be cleared by setting null`() {
        val buf = TerminalBuffer()
        var count = 0
        val listener = TerminalBuffer.ChangeListener { count++ }
        buf.setChangeListener(listener)
        buf.setChangeListener(null)
        // Even when write() is populated later, a null listener must not be invoked.
        // For now this test exists to lock the null-contract.
        assertEquals(0, count)
    }

    @Test
    fun `lineAt returns independent cell instances across lines`() {
        val buf = TerminalBuffer(initialCols = 3, initialRows = 2)
        val l0c0 = buf.cellAt(0, 0)!!
        val l1c0 = buf.cellAt(1, 0)!!
        // Sanity: each cell is its own TermCell instance (required for in-place mutation)
        l0c0.ch = "X"
        assertEquals("X", buf.cellAt(0, 0)!!.ch)
        assertEquals(" ", l1c0.ch)
    }

    @Test
    fun `resize is NOOP in Step 3 (Lépés 5-ig)`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 24)
        buf.resize(120, 40)
        assertEquals(80, buf.cols)
        assertEquals(24, buf.rows)
    }

    // ── Lépés 3: NORMAL ág — print + control karakterek ──────────────────────

    @Test
    fun `writeString prints ASCII into row 0 and advances cursor`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 24)
        buf.writeString("hello")
        val line = buf.lineAt(0)!!
        assertEquals("h", line[0].ch)
        assertEquals("e", line[1].ch)
        assertEquals("l", line[2].ch)
        assertEquals("l", line[3].ch)
        assertEquals("o", line[4].ch)
        assertEquals(0, buf.cursorRow)
        assertEquals(5, buf.cursorCol)
    }

    @Test
    fun `cr resets cursor column without advancing row`() {
        val buf = TerminalBuffer()
        buf.writeString("abc\r")
        assertEquals(0, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
        assertEquals("a", buf.cellAt(0, 0)!!.ch)
    }

    @Test
    fun `lf advances cursor row without resetting column`() {
        val buf = TerminalBuffer()
        buf.writeString("abc\n")
        assertEquals(1, buf.cursorRow)
        assertEquals(3, buf.cursorCol)
    }

    @Test
    fun `crlf wraps to the beginning of the next line`() {
        val buf = TerminalBuffer()
        buf.writeString("line1\r\nline2")
        assertEquals("l", buf.cellAt(0, 0)!!.ch)
        assertEquals("l", buf.cellAt(1, 0)!!.ch)
        assertEquals("2", buf.cellAt(1, 4)!!.ch)
        assertEquals(1, buf.cursorRow)
        assertEquals(5, buf.cursorCol)
    }

    @Test
    fun `backspace moves cursor left but not below zero`() {
        val buf = TerminalBuffer()
        buf.writeString("ab\b")
        assertEquals(1, buf.cursorCol)
        buf.writeString("\b\b\b\b")
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `tab aligns cursor to next 8-column boundary`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 24)
        buf.writeString("a\t")
        assertEquals(8, buf.cursorCol)
        buf.writeString("b\t")
        assertEquals(16, buf.cursorCol)
    }

    @Test
    fun `wraparound past last col triggers automatic line feed`() {
        val buf = TerminalBuffer(initialCols = 3, initialRows = 5)
        buf.writeString("abcd")
        // 'a' at (0,0), 'b' at (0,1), 'c' at (0,2), then wrap: 'd' at (1,0)
        assertEquals("a", buf.cellAt(0, 0)!!.ch)
        assertEquals("b", buf.cellAt(0, 1)!!.ch)
        assertEquals("c", buf.cellAt(0, 2)!!.ch)
        assertEquals("d", buf.cellAt(1, 0)!!.ch)
        assertEquals(1, buf.cursorRow)
        assertEquals(1, buf.cursorCol)
    }

    @Test
    fun `lineFeed at bottom scrolls screen up and appends to scrollback`() {
        val buf = TerminalBuffer(initialCols = 5, initialRows = 3)
        buf.writeString("R1\r\nR2\r\nR3\r\nR4")
        // After writing 4 lines into 3-row screen: first "R1" moves to scrollback
        assertEquals(1, buf.scrollbackSize)
        assertEquals("R", buf.cellAt(0, 0)!!.ch)   // scrollback[0] = "R1"
        assertEquals("1", buf.cellAt(0, 1)!!.ch)
        // Screen now holds R2, R3, R4
        assertEquals("R", buf.cellAt(1, 0)!!.ch)   // screen[0] = "R2"
        assertEquals("2", buf.cellAt(1, 1)!!.ch)
        assertEquals("R", buf.cellAt(3, 0)!!.ch)   // screen[2] = "R4"
        assertEquals("4", buf.cellAt(3, 1)!!.ch)
    }

    @Test
    fun `scrollback capped at maxScrollback`() {
        val buf = TerminalBuffer(initialCols = 3, initialRows = 2, maxScrollback = 3)
        // Write 10 lines — scrollback should hold at most 3
        repeat(10) { buf.writeString("X\r\n") }
        assertTrue(
            "scrollbackSize ${buf.scrollbackSize} exceeds cap",
            buf.scrollbackSize <= 3,
        )
    }

    @Test
    fun `writeString invokes ChangeListener once per call`() {
        val buf = TerminalBuffer()
        var count = 0
        buf.setChangeListener { count++ }
        buf.writeString("hello")
        assertEquals(1, count)
        buf.writeString("world")
        assertEquals(2, count)
    }

    @Test
    fun `bel and SO SI are consumed silently`() {
        val buf = TerminalBuffer()
        buf.writeString("abcd")
        assertEquals("a", buf.cellAt(0, 0)!!.ch)
        assertEquals("b", buf.cellAt(0, 1)!!.ch)
        assertEquals("c", buf.cellAt(0, 2)!!.ch)
        assertEquals("d", buf.cellAt(0, 3)!!.ch)
        assertEquals(4, buf.cursorCol)
    }

    // ── Lépés 4: CSI / SGR / módok / alt-screen ──────────────────────────────

    private val ESC = "\u001B"

    @Test
    fun `SGR 31 sets red foreground on subsequent printed chars`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[31mX")
        val cell = buf.cellAt(0, 0)!!
        assertEquals("X", cell.ch)
        assertEquals(TermColor.rgb(0xAA, 0, 0), cell.fg)
    }

    @Test
    fun `SGR 0 resets all attributes`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[1;31mBold" + ESC + "[0mplain")
        val bold = buf.cellAt(0, 0)!!
        val plain = buf.cellAt(0, 4)!!
        assertTrue(bold.bold)
        assertEquals(TermColor.rgb(0xAA, 0, 0), bold.fg)
        assertFalse(plain.bold)
        assertEquals(TermColor.DEFAULT_FG, plain.fg)
    }

    @Test
    fun `SGR bare m equals SGR 0 reset`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[1;31mA" + ESC + "[mB")
        val a = buf.cellAt(0, 0)!!
        val b = buf.cellAt(0, 1)!!
        assertTrue(a.bold)
        assertFalse(b.bold)
        assertEquals(TermColor.DEFAULT_FG, b.fg)
    }

    @Test
    fun `SGR 38_5_196 sets xterm256 red foreground`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[38;5;196mX")
        val cell = buf.cellAt(0, 0)!!
        assertEquals(TermColor.rgb(255, 0, 0), cell.fg)
    }

    @Test
    fun `SGR 38_2_r_g_b sets truecolor foreground`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[38;2;17;34;51mX")
        val cell = buf.cellAt(0, 0)!!
        assertEquals(TermColor.rgb(17, 34, 51), cell.fg)
    }

    @Test
    fun `CSI H positions cursor 1-based`() {
        val buf = TerminalBuffer(initialCols = 20, initialRows = 10)
        buf.writeString(ESC + "[5;7H")
        assertEquals(4, buf.cursorRow)
        assertEquals(6, buf.cursorCol)
    }

    @Test
    fun `CSI H without params homes cursor`() {
        val buf = TerminalBuffer()
        buf.writeString("abc\r\ndef")
        buf.writeString(ESC + "[H")
        assertEquals(0, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `CSI A moves cursor up one by default`() {
        val buf = TerminalBuffer()
        buf.writeString("\r\n\r\n\r\nX")
        val rowBefore = buf.cursorRow
        buf.writeString(ESC + "[A")
        assertEquals(rowBefore - 1, buf.cursorRow)
    }

    @Test
    fun `CSI K 0 erases from cursor to end of line`() {
        val buf = TerminalBuffer(initialCols = 10, initialRows = 3)
        buf.writeString("abcdefghij")
        buf.writeString(ESC + "[5G")
        buf.writeString(ESC + "[0K")
        assertEquals("a", buf.cellAt(0, 0)!!.ch)
        assertEquals("d", buf.cellAt(0, 3)!!.ch)
        assertEquals(" ", buf.cellAt(0, 4)!!.ch)
        assertEquals(" ", buf.cellAt(0, 9)!!.ch)
    }

    @Test
    fun `CSI J 2 clears screen and pushes to scrollback when not alt-screen`() {
        val buf = TerminalBuffer(initialCols = 5, initialRows = 3)
        buf.writeString("ABC")
        buf.writeString(ESC + "[2J")
        assertEquals(1, buf.scrollbackSize)
        assertEquals("A", buf.cellAt(0, 0)!!.ch)
        (0 until 3).forEach { r ->
            assertEquals(" ", buf.cellAt(buf.scrollbackSize + r, 0)!!.ch)
        }
    }

    @Test
    fun `CSI question mark 1 h enables app cursor keys`() {
        val buf = TerminalBuffer()
        assertFalse(buf.appCursorKeys)
        buf.writeString(ESC + "[?1h")
        assertTrue(buf.appCursorKeys)
        buf.writeString(ESC + "[?1l")
        assertFalse(buf.appCursorKeys)
    }

    @Test
    fun `CSI question mark 25 l hides cursor`() {
        val buf = TerminalBuffer()
        assertFalse(buf.cursorHidden)
        buf.writeString(ESC + "[?25l")
        assertTrue(buf.cursorHidden)
        buf.writeString(ESC + "[?25h")
        assertFalse(buf.cursorHidden)
    }

    @Test
    fun `CSI question mark 1049 h enters alt screen and saves main content`() {
        val buf = TerminalBuffer(initialCols = 5, initialRows = 3)
        buf.writeString("main")
        assertFalse(buf.altScreenActive)
        buf.writeString(ESC + "[?1049h")
        assertTrue(buf.altScreenActive)
        assertEquals(" ", buf.cellAt(buf.scrollbackSize, 0)!!.ch)
        buf.writeString("alt!")
        buf.writeString(ESC + "[?1049l")
        assertFalse(buf.altScreenActive)
        assertEquals("m", buf.cellAt(buf.scrollbackSize, 0)!!.ch)
        assertEquals("a", buf.cellAt(buf.scrollbackSize, 1)!!.ch)
    }

    @Test
    fun `CSI question mark 2004 toggles bracketed paste`() {
        val buf = TerminalBuffer()
        assertFalse(buf.bracketedPasteMode)
        buf.writeString(ESC + "[?2004h")
        assertTrue(buf.bracketedPasteMode)
        buf.writeString(ESC + "[?2004l")
        assertFalse(buf.bracketedPasteMode)
    }

    @Test
    fun `ESC 7 and ESC 8 save-and-restore cursor`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[5;10H")
        buf.writeString(ESC + "7")
        buf.writeString(ESC + "[1;1H")
        buf.writeString(ESC + "8")
        assertEquals(4, buf.cursorRow)
        assertEquals(9, buf.cursorCol)
    }

    @Test
    fun `ESC c fullReset clears state`() {
        val buf = TerminalBuffer()
        buf.writeString(ESC + "[1;31mX")
        buf.writeString(ESC + "[?25l")
        buf.writeString(ESC + "c")
        val cell = buf.cellAt(0, 0)!!
        assertEquals(" ", cell.ch)
        assertFalse(cell.bold)
        assertEquals(TermColor.DEFAULT_FG, cell.fg)
        assertFalse(buf.cursorHidden)
    }

    @Test
    fun `clearScreen empties main screen without touching scrollback`() {
        val buf = TerminalBuffer(initialCols = 4, initialRows = 3)
        buf.writeString("R1\r\nR2\r\nR3\r\nR4")
        val oldScrollback = buf.scrollbackSize
        buf.clearScreen()
        assertEquals(oldScrollback, buf.scrollbackSize)
        assertEquals(0, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
        assertEquals(" ", buf.cellAt(buf.scrollbackSize, 0)!!.ch)
    }

    @Test
    fun `CSI r sets scroll region and homes cursor`() {
        val buf = TerminalBuffer(initialCols = 10, initialRows = 10)
        buf.writeString(ESC + "[2;8r")
        assertEquals(0, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `printChar writes current default style into cell`() {
        val buf = TerminalBuffer()
        buf.writeString("A")
        val cell = buf.cellAt(0, 0)!!
        assertEquals(TermColor.DEFAULT_FG, cell.fg)
        assertEquals(TermColor.DEFAULT_BG, cell.bg)
        assertFalse(cell.bold)
    }

    @Test
    fun `emoji surrogate pair stored as single cell`() {
        val buf = TerminalBuffer()
        buf.writeString("🔥X")
        assertEquals("🔥", buf.cellAt(0, 0)!!.ch)
        assertEquals("X", buf.cellAt(0, 1)!!.ch)
        assertEquals(2, buf.cursorCol)
    }
}
