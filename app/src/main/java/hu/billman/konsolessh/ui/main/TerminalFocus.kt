package hu.billman.konsolessh.ui.main

import android.view.KeyEvent
import androidx.compose.foundation.pager.PagerState
import hu.billman.konsolessh.terminal.TerminalView

/**
 * Which terminal the key bar, zoom and hardware keys act on: the view of the
 * pager's current page. Each page registers its view while it is composed,
 * so a page scrolled far away, or a screen on top, leaves no stale view here.
 */
class TerminalFocus {

    /** The pager and its pages, as last composed. */
    var pager: PagerState? = null
    var pages: List<PageKey> = emptyList()

    private val terminals = mutableMapOf<PageKey, TerminalView>()

    fun register(page: PageKey, view: TerminalView?) {
        if (view == null) terminals.remove(page) else terminals[page] = view
    }

    fun currentTerminal(): TerminalView? = currentPage()?.let(terminals::get)

    fun currentTabId(): String? = (currentPage() as? PageKey.Tab)?.id

    private fun currentPage(): PageKey? = pager?.let { pages.getOrNull(it.currentPage) }

    /**
     * Gives a hardware key to the current terminal, wherever the focus is, so
     * typing works after tapping a key bar button. The system keys are left
     * to the system. Returns whether the terminal used the key.
     */
    fun dispatchKey(event: KeyEvent): Boolean {
        val terminal = currentTerminal().takeIf { event.keyCode !in SYSTEM_KEYS } ?: return false
        return terminal.isAttachedToWindow && terminal.dispatchKeyEvent(event)
    }

    private companion object {
        val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
        )
    }
}
