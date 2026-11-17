package hu.billman.konsolessh.terminal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertNotEquals
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

/**
 * What the terminal draws, checked on pixels: cell colours, glyphs, the
 * cursor and the selection. The screen is filled first, so the top visual
 * row is the screen's first row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1080dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalViewRenderingTest {

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
        output("x\r\n".repeat(LINES) + "\u001b[2J\u001b[H")
    }

    private fun output(text: String) {
        val bytes = text.toByteArray()
        view.append(bytes, bytes.size, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    // The first layout picks the font so that the columns fill the width.
    private val cellW get() = view.width.toFloat() / view.currentCols
    private val cellH get() = view.height.toFloat() / view.currentRows

    private fun centre(bitmap: Bitmap, col: Int, row: Int): Int =
        bitmap.getPixel(((col + 0.5f) * cellW).toInt(), ((row + 0.5f) * cellH * CELL_FILL).toInt())

    /** Any pixel of the cell for which [test] holds. */
    private fun anyPixel(bitmap: Bitmap, col: Int, row: Int, test: (Int) -> Boolean): Boolean {
        val x0 = (col * cellW).toInt()
        val y0 = (row * cellH * CELL_FILL).toInt()
        for (x in x0 until (x0 + cellW * CELL_FILL).toInt()) {
            for (y in y0 until (y0 + cellH * CELL_FILL).toInt()) if (test(bitmap.getPixel(x, y))) return true
        }
        return false
    }

    private fun isRed(c: Int) = Color.red(c) > STRONG && Color.green(c) < WEAK && Color.blue(c) < WEAK
    private fun isGreen(c: Int) = Color.green(c) > STRONG && Color.red(c) < WEAK && Color.blue(c) < WEAK

    // The selection colour is translucent: over black it is about (44, 81, 138).
    private fun isSelection(c: Int) = Color.blue(c) > Color.red(c) + MARGIN && Color.blue(c) > Color.green(c) + MARGIN

    @Test
    fun `a background colour fills its cells`() {
        output("\u001b[3;1H\u001b[41m   \u001b[0m")
        val bitmap = render()
        assertTrue(isRed(centre(bitmap, 1, 2)))
        assertTrue(isRed(centre(bitmap, 2, 2)))
        assertTrue(!isRed(centre(bitmap, 4, 2)))
    }

    @Test
    fun `reverse video swaps the colours`() {
        output("\u001b[3;1H\u001b[31;7m   \u001b[0m")
        assertTrue(isRed(centre(render(), 1, 2)))
    }

    @Test
    fun `a glyph is drawn in its foreground colour`() {
        output("\u001b[3;1H\u001b[32mMMMM\u001b[0m")
        val bitmap = render()
        assertTrue(anyPixel(bitmap, 1, 2, ::isGreen))
        assertTrue(!anyPixel(bitmap, 6, 2, ::isGreen))
    }

    @Test
    fun `the cursor blinks`() {
        val first = centre(render(), 0, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(BLINK_MS))
        val second = centre(render(), 0, 0)
        assertNotEquals(first, second)
    }

    @Test
    fun `a selected cell is highlighted`() {
        output("\u001b[3;1Hhello")
        val x = 1.5f * cellW
        val y = 2.5f * cellH * CELL_FILL
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).also { view.onTouchEvent(it) }.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HOLD_MS))
        MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0).also { view.onTouchEvent(it) }.recycle()
        assertTrue(anyPixel(render(), 1, 2, ::isSelection))
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1600
        const val LINES = 200
        const val HOLD_MS = 600L
        const val BLINK_MS = 650L
        const val STRONG = 150
        const val WEAK = 80
        const val MARGIN = 40

        /** The rows fill the height only up to less than one cell; stay inside the drawn grid. */
        const val CELL_FILL = 0.98f
    }
}
