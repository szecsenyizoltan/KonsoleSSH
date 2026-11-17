package hu.billman.konsolessh.ui.main

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import hu.billman.konsolessh.R
import hu.billman.konsolessh.ui.common.SshErrorMessages
import hu.billman.konsolessh.ui.common.ToastHostState
import java.io.IOException
import java.util.Locale

/**
 * Starts an upload: finds the file's name and size and opens it through the
 * application's content resolver, since the upload runs in the ViewModel
 * and may outlive the activity.
 */
class UploadController(context: Context, private val viewModel: MainViewModel) {

    private val resolver = context.applicationContext.contentResolver

    fun upload(tabId: String?, uri: Uri) {
        val name = displayName(uri) ?: DEFAULT_NAME
        viewModel.startUpload(tabId, name, size(uri)) {
            resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        }
    }

    private fun displayName(uri: Uri): String? =
        query(uri, OpenableColumns.DISPLAY_NAME) { it.getString(0) }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun size(uri: Uri): Long = query(uri, OpenableColumns.SIZE) { it.getLong(0) }?.takeIf { it > 0 } ?: -1L

    private fun <T> query(uri: Uri, column: String, read: (android.database.Cursor) -> T): T? =
        resolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) read(c) else null
        }

    private companion object {
        const val DEFAULT_NAME = "upload.bin"
    }
}

/**
 * Draws [upload]: a progress dialog while it runs, then a toast with the
 * outcome; a finished upload can be undone from its toast for a moment.
 */
@Composable
fun UploadStatus(
    upload: MainViewModel.Upload?,
    toasts: ToastHostState,
    onShown: () -> Unit,
    onUndo: (MainViewModel.Upload.Done) -> Unit,
) {
    when (upload) {
        null -> Unit
        is MainViewModel.Upload.Running -> {
            val (name, done, total) = upload
            val amount = if (total > 0L) progressText(done, total) else humanSize(done)
            UploadProgressDialog(
                name = name,
                status = stringResource(R.string.upload_status, name, amount),
                progress = if (total > 0L) done.toFloat() / total else null,
            )
        }
        else -> {
            val text = when (upload) {
                MainViewModel.Upload.NotConnected ->
                    stringResource(R.string.upload_failed, stringResource(R.string.upload_not_connected))
                is MainViewModel.Upload.Failed ->
                    stringResource(R.string.upload_failed, SshErrorMessages.text(LocalContext.current, upload.error))
                is MainViewModel.Upload.Done -> stringResource(R.string.upload_done, HOME_DIR, upload.name)
            }
            val undo = stringResource(R.string.undo)
            LaunchedEffect(upload) {
                if (upload is MainViewModel.Upload.Done) {
                    toasts.showWithAction(text, undo, UNDO_TIMEOUT_MS) { onUndo(upload) }
                } else {
                    toasts.show(text)
                }
                onShown()
            }
        }
    }
}

@Composable
private fun progressText(done: Long, total: Long): String = if (total >= MB) {
    stringResource(R.string.upload_progress_mb, done / MB.toDouble(), total / MB.toDouble())
} else {
    stringResource(R.string.upload_progress_kb, (done / KB).toInt(), (total / KB).toInt())
}

private fun humanSize(bytes: Long): String = when {
    bytes < KB -> "$bytes B"
    bytes < MB -> "${bytes / KB} KB"
    bytes < GB -> String.format(Locale.ROOT, "%.1f MB", bytes / MB.toDouble())
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / GB.toDouble())
}

private const val HOME_DIR = "~"
private const val UNDO_TIMEOUT_MS = 3000L
private const val KB = 1024L
private const val MB = KB * 1024L
private const val GB = MB * 1024L
