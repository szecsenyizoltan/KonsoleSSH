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
    fun `write is NOOP in Step 2 and does not change cursor or state`() {
        val buf = TerminalBuffer()
        buf.write("hello".toByteArray(Charsets.UTF_8))
        assertEquals(0, buf.cursorRow)
        assertEquals(0, buf.cursorCol)
        assertEquals(" ", buf.cellAt(0, 0)!!.ch)
    }

    @Test
    fun `resize is NOOP in Step 2`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 24)
        buf.resize(120, 40)
        // Still the original dimensions until Step 5
        assertEquals(80, buf.cols)
        assertEquals(24, buf.rows)
    }
}
