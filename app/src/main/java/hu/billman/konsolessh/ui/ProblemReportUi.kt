package hu.billman.konsolessh.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import hu.billman.konsolessh.R
import hu.billman.konsolessh.diagnostics.AndroidProblemReports
import java.io.File

/** Shares a problem report through the system share sheet (mail, messenger, file). */
object ProblemReportUi {

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
