package hu.billman.konsolessh.terminal

import android.view.KeyEvent

/**
 * The bytes the terminal sends for a key, or for typed and pasted text, in
 * xterm's encoding. Pure functions of the key and the terminal modes, so
 * the hardware keyboard, the soft keyboard and the paste all agree.
 */
internal object KeyEncoder {

    private const val CR: Byte = 13
    private const val BACKSPACE: Byte = 127

    /**
     * [unicodeChar] is the character the key types with its modifiers
     * ([KeyEvent.getUnicodeChar]), 0 if none. Null: not a key the terminal
     * handles, so the system may.
     */
    fun key(keyCode: Int, unicodeChar: Int, appCursorKeys: Boolean): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> byteArrayOf(CR)
        // Android's KEYCODE_DEL is misnamed: it is the Backspace key.
        KeyEvent.KEYCODE_DEL -> byteArrayOf(BACKSPACE)
        // The actual Delete (forward delete) key: the xterm Del sequence.
        KeyEvent.KEYCODE_FORWARD_DEL -> KeySequences.escape("[3~")
        KeyEvent.KEYCODE_TAB -> byteArrayOf(KeySequences.TAB)
        KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(KeySequences.ESC)
        KeyEvent.KEYCODE_DPAD_UP -> cursorKey('A', appCursorKeys)
        KeyEvent.KEYCODE_DPAD_DOWN -> cursorKey('B', appCursorKeys)
        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorKey('C', appCursorKeys)
        KeyEvent.KEYCODE_DPAD_LEFT -> cursorKey('D', appCursorKeys)
        KeyEvent.KEYCODE_MOVE_HOME -> KeySequences.escape("[H")
        KeyEvent.KEYCODE_MOVE_END -> KeySequences.escape("[F")
        KeyEvent.KEYCODE_PAGE_UP -> KeySequences.escape("[5~")
        KeyEvent.KEYCODE_PAGE_DOWN -> KeySequences.escape("[6~")
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            KeySequences.escape(KeySequences.FUNCTION_KEYS[keyCode - KeyEvent.KEYCODE_F1])
        else -> unicodeChar.takeIf { it > 0 }?.let { byteArrayOf(it.toByte()) }
    }

    /** An arrow key; [dir] is 'A' (up), 'B' (down), 'C' (right) or 'D' (left). */
    fun cursorKey(dir: Char, appCursorKeys: Boolean): ByteArray =
        KeySequences.escape(if (appCursorKeys) "O$dir" else "[$dir")

    /** One Backspace, as the soft keyboard's delete sends it. */
    fun backspace(): ByteArray = byteArrayOf(BACKSPACE)

    /**
     * Typed or pasted text. Newlines become carriage returns, as the Enter
     * key sends; in bracketed paste mode the text is marked as a paste, so
     * the shell does not run it line by line.
     */
    fun text(text: String, bracketedPaste: Boolean): ByteArray {
        val s = text.replace("\n", "\r")
        return (if (bracketedPaste) "\u001B[200~$s\u001B[201~" else s).toByteArray(Charsets.UTF_8)
    }
}
