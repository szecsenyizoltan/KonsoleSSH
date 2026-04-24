package hu.billman.konsolessh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM teszt — nincs Android-függőség, nincs Robolectric.
 * A Buffer-rétegnek ezt kell bizonyítania: a típusok platformfüggetlenek.
 */
class TerminalTypesTest {

    @Test
    fun `DEFAULT_FG matches android Color rgb 204 204 204 bit pattern`() {
        // android.graphics.Color.rgb(204, 204, 204) == 0xFFCCCCCC
        assertEquals(0xFFCCCCCC.toInt(), TermColor.DEFAULT_FG.argb)
    }

    @Test
    fun `DEFAULT_BG is opaque black`() {
        assertEquals(0xFF000000.toInt(), TermColor.DEFAULT_BG.argb)
    }

    @Test
    fun `TermColor is a value class holding single Int`() {
        val c = TermColor(0x12345678)
        assertEquals(0x12345678, c.argb)
    }

    @Test
    fun `TermCell blank returns a cell with space char and default colors`() {
        val c = TermCell.blank()
        assertEquals(" ", c.ch)
        assertEquals(TermColor.DEFAULT_FG, c.fg)
        assertEquals(TermColor.DEFAULT_BG, c.bg)
        assertFalse(c.bold)
        assertFalse(c.underline)
        assertFalse(c.reverse)
    }

    @Test
    fun `TermCell blank returns distinct instances for in-place mutation`() {
        val a = TermCell.blank()
        val b = TermCell.blank()
        a.ch = "X"
        assertEquals(" ", b.ch)
    }

    @Test
    fun `TermCell fields are mutable for in-place SGR writes`() {
        val c = TermCell.blank()
        c.ch = "A"
        c.fg = TermColor(0xFFFF0000.toInt())
        c.bold = true
        assertEquals("A", c.ch)
        assertEquals(0xFFFF0000.toInt(), c.fg.argb)
        assertTrue(c.bold)
    }

    @Test
    fun `TerminalSnapshot bundles all metadata`() {
        val snap = TerminalSnapshot(
            size = TermSize(cols = 80, rows = 24),
            scrollbackSize = 42,
            cursor = CursorPos(row = 5, col = 10),
            cursorHidden = true,
            altScreenActive = false,
            appCursorKeys = true,
            bracketedPasteMode = false,
        )
        assertEquals(80, snap.size.cols)
        assertEquals(24, snap.size.rows)
        assertEquals(42, snap.scrollbackSize)
        assertEquals(5, snap.cursor.row)
        assertEquals(10, snap.cursor.col)
        assertTrue(snap.cursorHidden)
        assertFalse(snap.altScreenActive)
        assertTrue(snap.appCursorKeys)
        assertFalse(snap.bracketedPasteMode)
    }
}
