package hu.billman.konsolessh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionTest {

    private val lines = listOf("hello world   ", "second line   ", "third")
        .map { text -> Array(COLS) { c -> TermCell(ch = text.getOrNull(c)?.toString() ?: " ") } }

    private fun TerminalSelection.text() = text(COLS) { lines.getOrNull(it) }

    @Test
    fun `nothing is selected until a selection starts`() {
        assertEquals("", TerminalSelection().text())
    }

    @Test
    fun `a selection within one line`() {
        val sel = TerminalSelection().apply {
            start(0, 6)
            extendTo(0, 10)
        }
        assertEquals("world", sel.text())
    }

    @Test
    fun `a backward selection over lines reads in order, without trailing blanks`() {
        val sel = TerminalSelection().apply {
            start(1, 5)
            extendTo(0, 6)
        }
        assertEquals("world\nsecond", sel.text())
        assertTrue(sel.bounds().contentEquals(intArrayOf(0, 6, 1, 5)))
    }

    @Test
    fun `cleared, it selects nothing`() {
        val sel = TerminalSelection().apply { start(0, 0) }
        sel.clear()
        assertFalse(sel.active)
        assertEquals("", sel.text())
    }

    @Test
    fun `contains follows reading order`() {
        val bounds = intArrayOf(1, 5, 3, 2)
        assertFalse(TerminalSelection.contains(bounds, 1, 4))
        assertTrue(TerminalSelection.contains(bounds, 1, 79))
        assertTrue(TerminalSelection.contains(bounds, 2, 0))
        assertTrue(TerminalSelection.contains(bounds, 3, 2))
        assertFalse(TerminalSelection.contains(bounds, 3, 3))
        assertFalse(TerminalSelection.contains(bounds, 4, 0))
    }

    private companion object {
        const val COLS = 80
    }
}
