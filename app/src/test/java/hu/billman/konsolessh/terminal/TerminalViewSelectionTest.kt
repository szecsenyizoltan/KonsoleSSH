package hu.billman.konsolessh.terminal

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
import java.time.Duration

/** Long press selection: which touches start, keep and end a selection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewSelectionTest {

    private lateinit var view: TerminalView

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
        // Enough lines to fill the screen, so the touched top row holds text.
        val text = "hello world\r\n".repeat(LINES).toByteArray()
        view.append(text, text.size, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun hold() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HOLD_MS))

    @Test
    fun `lifting the finger after a long press keeps the selection`() {
        touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        hold()
        touch(MotionEvent.ACTION_UP, 5f, 5f)

        assertTrue(view.getSelectedText().isNotEmpty())
    }

    @Test
    fun `a later tap ends the selection`() {
        touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        hold()
        touch(MotionEvent.ACTION_UP, 5f, 5f)

        touch(MotionEvent.ACTION_DOWN, 5f, 5f)
        touch(MotionEvent.ACTION_UP, 5f, 5f)

        assertEquals("", view.getSelectedText())
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1600
        const val HOLD_MS = 600L
        const val LINES = 200
    }
}
