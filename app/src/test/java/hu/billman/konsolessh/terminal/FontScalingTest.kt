package hu.billman.konsolessh.terminal

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FontScalingTest {

    private fun metrics() = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics

    @Test
    @Config(sdk = [34])
    fun `a large system font scales the terminal non-linearly on Android 14`() {
        RuntimeEnvironment.setFontScale(2f)
        val m = metrics()
        val linear = 20f * m.density * 2f
        assertTrue("non-linear scaling stays below the linear size", FontScaling.spToPx(20f, m) < linear)
    }

    @Test
    @Config(sdk = [34])
    fun `pixels convert back to the same sp on Android 14`() {
        RuntimeEnvironment.setFontScale(2f)
        val m = metrics()
        assertEquals(14f, FontScaling.pxToSp(FontScaling.spToPx(14f, m), m), 0.05f)
    }

    @Test
    @Config(sdk = [33])
    fun `before Android 14 the scale is linear and converts back`() {
        RuntimeEnvironment.setFontScale(2f)
        val m = metrics()
        assertEquals(20f * m.density * 2f, FontScaling.spToPx(20f, m), 0.01f)
        assertEquals(14f, FontScaling.pxToSp(FontScaling.spToPx(14f, m), m), 0.01f)
    }

    @Test
    @Config(sdk = [34])
    fun `at the default font scale nothing changes`() {
        val m = metrics()
        assertEquals(Configuration().apply { setToDefaults() }.fontScale, 1f, 0f)
        assertEquals(12f * m.density, FontScaling.spToPx(12f, m), 0.01f)
    }
}
