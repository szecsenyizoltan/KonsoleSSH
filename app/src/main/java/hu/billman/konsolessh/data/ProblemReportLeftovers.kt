package hu.billman.konsolessh.data

import android.content.Context
import java.io.File

/**
 * Up to 2.x the app kept crash and ANR reports, with stack traces, in its
 * files directory and their state in SharedPreferences. Crashes are now
 * followed in Play Console only, so whatever a 2.x install left there is
 * deleted.
 */
internal object ProblemReportLeftovers {

    private const val DIR = "diagnostics"
    private const val PREFS = "konsole_diagnostics"

    fun delete(context: Context) {
        File(context.filesDir, DIR).deleteRecursively()
        context.deleteSharedPreferences(PREFS)
    }
}
