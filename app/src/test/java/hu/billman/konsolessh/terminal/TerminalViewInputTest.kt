package hu.billman.konsolessh.terminal

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the terminal sends for hardware keys, the soft keyboard and pasted text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewInputTest {

    private lateinit var view: TerminalView
    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = TerminalView(activity)
        activity.setContentView(view)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, WIDTH, HEIGHT)
        view.onKeyInput = { sent += String(it, Charsets.UTF_8) }
    }

    private fun output(text: String) {
        val bytes = text.toByteArray()
        view.append(bytes, bytes.size, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun press(keyCode: Int, meta: Int = 0): Boolean {
        val down = view.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        val up = view.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
        return down && up
    }

    private fun ime(): InputConnection = view.onCreateInputConnection(EditorInfo())

    @Test
    fun `hardware keys send xterm sequences`() {
        val keys = listOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_A,
        )
        keys.forEach { assertTrue(press(it)) }
        assertEquals(
            listOf(
                "\r", "\u007f", "\u001b[3~", "\t", "\u001b", "\u001b[A", "\u001b[D", "\u001b[H", "\u001b[F",
                "\u001b[5~", "\u001b[6~", "\u001bOP", "\u001b[15~", "\u001b[24~", "a",
            ),
            sent,
        )
    }

    @Test
    fun `application cursor mode changes the arrow keys`() {
        output("\u001b[?1h")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(listOf("\u001bOC"), sent)
        assertEquals("\u001bOB", String(view.cursorKeyBytes('B')))
    }

    @Test
    fun `shift page up scrolls the view instead of sending`() {
        output("line\r\n".repeat(LINES))
        assertTrue(press(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.META_SHIFT_ON))
        assertTrue(press(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.META_SHIFT_ON))
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `the soft keyboard sends committed and composed text`() {
        val ic = ime()
        ic.commitText("hé", 1)
        ic.setComposingText("x", 1)
        ic.finishComposingText()
        assertEquals(listOf("hé", "x"), sent)
    }

    @Test
    fun `the soft keyboard backspace and keys work on an empty buffer`() {
        val ic = ime()
        ic.deleteSurroundingText(2, 0)
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals(listOf("\u007f", "\u007f", "\r"), sent)
    }

    @Test
    fun `newlines become carriage returns, wrapped in bracketed paste mode`() {
        view.pasteText("a\nb")
        ime().commitText("c\nd", 1)
        output("\u001b[?2004h")
        view.pasteText("a\nb")
        ime().commitText("c\nd", 1)
        assertEquals(
            listOf("a\rb", "c\rd", "\u001b[200~a\rb\u001b[201~", "\u001b[200~c\rd\u001b[201~"),
            sent,
        )
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1600
        const val LINES = 200
    }
}
