package hu.billman.konsolessh.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [ProblemReports] Androidon: az ANR-nyom az [ApplicationExitInfo]-ból jön
 * (API 30+), az összeomlás a [CrashRecorder] fájljából. A jelentés a
 * `files/diagnostics/report.txt`-be kerül, onnan osztható meg.
 */
class AndroidProblemReports(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProblemReports {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)
    private val reportFile = File(dir, "report.txt")
    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    /** A [CrashRecorder] ide ír. */
    val crashFile = File(dir, "crash.txt")

    override suspend fun collectNew(): File? = io {
        val sections = buildList {
            readCrash()?.let(::add)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) newAnr()?.let { add(anrSection(it)) }
        }
        if (sections.isNotEmpty()) {
            dir.mkdirs()
            reportFile.writeText(ProblemReportFormat.compose(header(), sections))
            prefs.edit { putBoolean(KEY_UNSEEN, true) }
        }
        reportFile.takeIf { prefs.getBoolean(KEY_UNSEEN, false) && it.exists() }
    }

    override suspend fun latest(): File? = io { reportFile.takeIf { it.exists() } }

    override suspend fun markSeen() = io { prefs.edit { putBoolean(KEY_UNSEEN, false) } }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { mutex.withLock { block() } }

    private fun readCrash(): ProblemReportFormat.Section? {
        if (!crashFile.exists()) return null
        val body = crashFile.readText()
        crashFile.delete()
        return ProblemReportFormat.Section("CRASH", body)
    }

    // Each exit is reported once: the newest timestamp already looked at is kept.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun newAnr(): ApplicationExitInfo? {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return null
        val lastSeen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = am.getHistoricalProcessExitReasons(appContext.packageName, 0, MAX_EXITS)
        exits.maxOfOrNull { it.timestamp }?.let { newest ->
            if (newest > lastSeen) prefs.edit { putLong(KEY_LAST_EXIT, newest) }
        }
        return exits.filter { it.reason == ApplicationExitInfo.REASON_ANR && it.timestamp > lastSeen }
            .maxByOrNull { it.timestamp }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun anrSection(anr: ApplicationExitInfo): ProblemReportFormat.Section {
        val trace = runCatching {
            anr.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val body = buildString {
            appendLine("time: ${format(anr.timestamp)}")
            appendLine("description: ${anr.description.orEmpty()}")
            appendLine("importance: ${anr.importance}")
            appendLine()
            append(trace ?: "(no trace available)")
        }
        return ProblemReportFormat.Section("ANR", body)
    }

    private fun header(): String {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return "KonsoleSSH ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)}) · " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private fun format(timestamp: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(timestamp))

    companion object {
        /** A FileProvider-ben is ez a könyvtár szerepel. */
        const val DIR = "diagnostics"
        private const val PREFS = "konsole_diagnostics"
        private const val KEY_LAST_EXIT = "last_exit_timestamp"
        private const val KEY_UNSEEN = "report_unseen"
        private const val MAX_EXITS = 10
    }
}
