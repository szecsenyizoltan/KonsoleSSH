package hu.billman.konsolessh.terminal

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/**
 * The soft keyboard's connection to the terminal. The terminal has no text
 * field, so whatever the keyboard writes into the editable is sent at once
 * and the editable is reset. It always keeps [SENTINEL], so a backspace on
 * an "empty" line still reaches the terminal.
 */
internal class TerminalInputConnection(
    view: View,
    private val send: (ByteArray) -> Unit,
    private val bracketedPaste: () -> Boolean,
    private val keyBytes: (KeyEvent) -> ByteArray?,
) : BaseInputConnection(view, true) {

    fun resetToSentinel() {
        val e = editable ?: return
        e.replace(0, e.length, SENTINEL)
        Selection.setSelection(e, SENTINEL.length)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.commitText(text, newCursorPosition)
        return sendWritten()
    }

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        return sendWritten()
    }

    private fun sendWritten(): Boolean {
        val str = editable?.toString()?.removePrefix(SENTINEL) ?: return true
        if (str.isNotEmpty()) send(KeyEncoder.text(str, bracketedPaste()))
        resetToSentinel()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength.coerceIn(0, 16)) { send(KeyEncoder.backspace()) }
        resetToSentinel()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val bytes = keyBytes(event)
            if (bytes != null) {
                send(bytes)
                return true
            }
        }
        return super.sendKeyEvent(event)
    }

    private companion object {
        /** Kept at the start of the editable, so a backspace always has something to delete. */
        const val SENTINEL = " "
    }
}
