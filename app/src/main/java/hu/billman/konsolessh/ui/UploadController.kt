package hu.billman.konsolessh.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import hu.billman.konsolessh.R
import java.io.IOException
import java.util.Locale

/**
 * The upload screen parts: picks the file's name and size, opens it through
 * the application's content resolver, and draws [MainViewModel.upload]. The
 * transfer itself runs in the ViewModel, so the activity may be recreated
 * meanwhile; a new controller shows the same progress.
 */
class UploadController(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val anchor: View,
) {

    private var dialog: AlertDialog? = null
    private var bar: ProgressBar? = null
    private var status: TextView? = null

    fun upload(tabId: String?, uri: Uri) {
        val name = displayName(uri) ?: DEFAULT_NAME
        // The application context: the upload may outlive this activity.
        val resolver = activity.applicationContext.contentResolver
        viewModel.startUpload(tabId, name, size(uri)) {
            resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        }
    }

    fun render(upload: MainViewModel.Upload?) {
        if (upload is MainViewModel.Upload.Running) {
            showProgress(upload)
            return
        }
        dismiss()
        when (upload) {
            null, is MainViewModel.Upload.Running -> return
            MainViewModel.Upload.NotConnected -> failed(activity.getString(R.string.upload_not_connected))
            is MainViewModel.Upload.Failed -> failed(upload.reason)
            is MainViewModel.Upload.Done -> KonsoleToast.showWithAction(
                anchor = anchor,
                message = activity.getString(R.string.upload_done, HOME_DIR, upload.name),
                actionLabel = activity.getString(R.string.undo),
                timeoutMs = UNDO_TIMEOUT_MS,
            ) { viewModel.undoUpload(upload) }
        }
        viewModel.onUploadShown()
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        bar = null
        status = null
    }

    private fun failed(reason: String) = KonsoleToast.show(anchor, activity.getString(R.string.upload_failed, reason))

    private fun showProgress(upload: MainViewModel.Upload.Running) {
        if (dialog == null) {
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_upload_progress, null)
            bar = view.findViewById(R.id.progressBar)
            status = view.findViewById(R.id.textStatus)
            dialog = AlertDialog.Builder(activity, R.style.KonsoleDialog)
                .setTitle(activity.getString(R.string.upload_title, upload.name))
                .setView(view)
                .setCancelable(false)
                .show()
        }
        val bar = bar ?: return
        val status = status ?: return
        val (name, done, total) = upload
        if (total > 0L) {
            bar.isIndeterminate = false
            bar.progress = ((done * PROGRESS_MAX) / total).toInt()
            status.text = activity.getString(R.string.upload_status, name, progressText(done, total))
        } else {
            bar.isIndeterminate = true
            status.text = activity.getString(R.string.upload_status, name, humanSize(done))
        }
    }

    private fun progressText(done: Long, total: Long): String = if (total >= MB) {
        activity.getString(R.string.upload_progress_mb, done / MB.toDouble(), total / MB.toDouble())
    } else {
        activity.getString(R.string.upload_progress_kb, (done / KB).toInt(), (total / KB).toInt())
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < KB -> "$bytes B"
        bytes < MB -> "${bytes / KB} KB"
        bytes < GB -> String.format(Locale.ROOT, "%.1f MB", bytes / MB.toDouble())
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / GB.toDouble())
    }

    private fun displayName(uri: Uri): String? =
        query(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun size(uri: Uri): Long = query(uri, OpenableColumns.SIZE) { it.getLong(0) }?.takeIf { it > 0 } ?: -1L

    private fun <T> query(uri: Uri, column: String, read: (android.database.Cursor) -> T): T? =
        activity.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) read(c) else null
        }

    private companion object {
        const val DEFAULT_NAME = "upload.bin"
        const val HOME_DIR = "~"
        const val UNDO_TIMEOUT_MS = 3000L
        const val PROGRESS_MAX = 1000L
        const val KB = 1024L
        const val MB = KB * 1024L
        const val GB = MB * 1024L
    }
}
