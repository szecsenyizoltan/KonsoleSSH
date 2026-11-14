package hu.billman.konsolessh.ui.keybar

import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.terminal.Modifier as Key

/** What the key bar needs from the screen around it. */
interface KeybarHost {
    /** The terminal of the current page; a fixed page has one too. */
    fun currentTerminal(): TerminalView?

    /** Input with the sticky modifiers applied. */
    fun sendKey(bytes: ByteArray)

    /** A control code that leaves the modifiers alone (the Ctrl row). */
    fun sendRaw(bytes: ByteArray)

    fun toggleModifier(modifier: Key)
    fun toggleKeyboard()
    fun pickUploadFile()
    fun showMessage(message: String)
}
