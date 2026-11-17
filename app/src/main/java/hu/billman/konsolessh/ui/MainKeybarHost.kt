package hu.billman.konsolessh.ui

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.common.ToastHostState
import hu.billman.konsolessh.ui.keybar.KeybarHost
import hu.billman.konsolessh.terminal.Modifier as Key

/**
 * Serves the key bar on the main screen: its keys go to the tab of the
 * current page. [view] is any view of the screen's window, for the soft
 * keyboard; [pickFile] opens the system file picker for an upload.
 */
class MainKeybarHost(
    private val focus: TerminalFocus,
    private val viewModel: MainViewModel,
    private val toasts: ToastHostState,
    private val view: View,
    private val pickFile: () -> Unit,
) : KeybarHost {

    override fun currentTerminal(): TerminalView? = focus.currentTerminal()

    override fun sendKey(bytes: ByteArray) {
        val tabId = focus.currentTabId() ?: return
        viewModel.send(tabId, bytes)
        currentTerminal()?.scrollToBottom()
    }

    override fun sendRaw(bytes: ByteArray) {
        val tabId = focus.currentTabId() ?: return
        viewModel.sendRaw(tabId, bytes)
        currentTerminal()?.scrollToBottom()
    }

    override fun toggleModifier(modifier: Key) = viewModel.modifiers.toggle(modifier)

    override fun pickUploadFile() = pickFile()

    override fun toggleKeyboard() {
        val imm = view.context.getSystemService<InputMethodManager>() ?: return
        val imeVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (imeVisible) {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        } else {
            currentTerminal()?.focusAndShowKeyboard()
        }
    }

    override fun showMessage(message: String) = toasts.show(message)
}
