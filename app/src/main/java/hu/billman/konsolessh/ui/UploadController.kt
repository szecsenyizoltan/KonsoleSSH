package hu.billman.konsolessh.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import hu.billman.konsolessh.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * SFTP-feltöltés az aktív fül home könyvtárába, folyamatjelzővel és
 * visszavonással. A tényleges átvitel a [MainViewModel]-en át a
 * munkamenet-kezelőben fut; itt csak a felület és a fájl megnyitása van.
 */
class UploadController(
    private val activity: AppCompatActivity,
    private val viewModel: MainViewModel,
    private val anchor: View,
) {

    fun upload(tabId: String?, uri: Uri) {
        if (tabId == null || !viewModel.isConnected(tabId)) {
            val reason = activity.getString(R.string.upload_not_connected)
            KonsoleToast.show(anchor, activity.getString(R.string.upload_failed, reason))
            return
        }
        val name = displayName(uri) ?: DEFAULT_NAME
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_upload_progress, null)
        val bar = view.findViewById<ProgressBar>(R.id.progressBar)
        val status = view.findViewById<TextView>(R.id.textStatus)
        status.text = name
        val dialog = AlertDialog.Builder(activity, R.style.KonsoleDialog)
            .setTitle(activity.getString(R.string.upload_title, name))
            .setView(view)
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch {
            val error = try {
                withContext(Dispatchers.IO) {
                    val input = activity.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
                    input.use {
                        viewModel.upload(tabId, it, name, size(uri)) { done, total ->
                            activity.runOnUiThread { showProgress(bar, status, name, done, total) }
                        }
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            dialog.dismiss()
            if (error == null) {
                KonsoleToast.showWithAction(
                    anchor = anchor,
                    message = activity.getString(R.string.upload_done, HOME_DIR, name),
                    actionLabel = activity.getString(R.string.undo),
                    timeoutMs = UNDO_TIMEOUT_MS,
                ) { undo(tabId, name) }
            } else {
                val reason = error.message ?: error.javaClass.simpleName
                KonsoleToast.show(anchor, activity.getString(R.string.upload_failed, reason))
            }
        }
    }

    private fun undo(tabId: String, remoteName: String) {
        activity.lifecycleScope.launch {
            val message = try {
                viewModel.deleteRemote(tabId, remoteName)
                activity.getString(R.string.upload_undone, remoteName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.getString(R.string.upload_undo_failed, e.message ?: e.javaClass.simpleName)
            }
            KonsoleToast.show(anchor, message)
        }
    }

    private fun showProgress(bar: ProgressBar, status: TextView, name: String, done: Long, total: Long) {
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
