package hu.billman.konsolessh.ui.main

import android.app.Activity
import android.view.KeyEvent
import androidx.compose.foundation.pager.PagerState
import hu.billman.konsolessh.terminal.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalFocusTest {

    private lateinit var activity: Activity
    private val focus = TerminalFocus()
    private val pages = PageKey.pages(listOf("a", "b"))

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        focus.pages = pages
    }

    // A pager on the page from the start: scrolling would wait for a layout, which needs a composition.
    private fun showPage(page: PageKey) {
        focus.pager = PagerState(currentPage = pages.indexOf(page)) { pages.size }
    }

    @Test
    fun `the current page's registered terminal is the current one`() {
        val tabA = TerminalView(activity)
        focus.register(PageKey.Tab("a"), tabA)

        showPage(PageKey.Tab("a"))

        assertSame(tabA, focus.currentTerminal())
        assertEquals("a", focus.currentTabId())
    }

    @Test
    fun `a page that left the composition has no terminal`() {
        focus.register(PageKey.Tab("b"), TerminalView(activity))
        focus.register(PageKey.Tab("b"), null)

        showPage(PageKey.Tab("b"))

        assertNull(focus.currentTerminal())
        assertEquals("b", focus.currentTabId())
    }

    @Test
    fun `a fixed page has a terminal but no tab`() {
        val welcome = TerminalView(activity)
        focus.register(PageKey.Welcome, welcome)

        showPage(PageKey.Welcome)

        assertSame(welcome, focus.currentTerminal())
        assertNull(focus.currentTabId())
    }

    @Test
    fun `system keys and keys without a terminal are left to the system`() {
        focus.register(PageKey.Tab("a"), TerminalView(activity))
        showPage(PageKey.Tab("a"))
        assertFalse(focus.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertFalse(focus.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))

        showPage(PageKey.Tab("b"))
        assertFalse(focus.dispatchKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
    }
}
