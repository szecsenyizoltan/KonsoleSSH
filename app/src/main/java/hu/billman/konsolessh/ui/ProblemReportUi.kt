package hu.billman.konsolessh.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import hu.billman.konsolessh.R
import hu.billman.konsolessh.diagnostics.AndroidProblemReports
import java.io.File

/** A hibajelentés felajánlása és megosztása (e-mail, üzenetküldő, fájl). */
object ProblemReportUi {

    /** Egyszer felajánlja a megosztást; [onDone] a döntés után fut (megosztás vagy elutasítás). */
    fun offer(activity: Activity, report: File, onDone: () -> Unit): Dialog =
        AlertDialog.Builder(activity, R.style.KonsoleDialog)
            .setTitle(R.string.report_title)
            .setMessage(R.string.report_message)
            .setPositiveButton(R.string.report_share) { _, _ ->
                share(activity, report)
                onDone()
            }
            .setNegativeButton(R.string.report_dismiss) { _, _ -> onDone() }
            .setCancelable(false)
            .show()

    fun share(activity: Activity, report: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.${AndroidProblemReports.DIR}",
            report,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.report_subject))
            // The chooser passes the read grant on only through ClipData.
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.report_share)))
    }
}
