package hu.billman.konsolessh.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProblemReportLeftoversTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun prefsFile() = File(context.applicationInfo.dataDir, "shared_prefs/konsole_diagnostics.xml")

    @Test
    fun `the reports of 2_x and their state are deleted, and nothing else`() {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        File(dir, "report.txt").writeText("trace")
        val prefs = context.getSharedPreferences("konsole_diagnostics", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("unseen", true).commit()
        val other = File(context.filesDir, "known_hosts").apply { writeText("kept") }

        ProblemReportLeftovers.delete(context)

        assertFalse(dir.exists())
        assertFalse(prefsFile().exists())
        assertTrue(other.exists())
    }

    @Test
    fun `nothing to delete is fine`() {
        ProblemReportLeftovers.delete(context)
        assertFalse(File(context.filesDir, "diagnostics").exists())
    }
}
