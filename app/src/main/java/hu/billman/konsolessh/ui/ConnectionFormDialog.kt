package hu.billman.konsolessh.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The connection form in a dialog. The form's state and rules live in
 * [NewConnectionViewModel], scoped to this dialog; this function only reads
 * key files and reports the saved connection's id through [onSaved].
 */
@Composable
fun ConnectionFormDialog(request: FormRequest, onSaved: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val viewModel = viewModel<NewConnectionViewModel>(
        viewModelStoreOwner = rememberDialogScope("connection_form:${request.id}"),
        factory = KonsoleViewModelFactory.from(context),
    )
    LaunchedEffect(viewModel) { viewModel.start(request.editId) }

    val saved by viewModel.savedId.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { saved?.let(onSaved) }

    val scope = rememberCoroutineScope()
    val pickKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { readKeyFile(context.contentResolver, uri, viewModel) }
    }

    // Not fitted to the system windows, so the form can move above the keyboard.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxWidth().safeDrawingPadding()) {
            val form by viewModel.form.collectAsStateWithLifecycle()
            val message by viewModel.message.collectAsStateWithLifecycle()
            val keyDialog by viewModel.keyDialog.collectAsStateWithLifecycle()
            val prompt by viewModel.prompt.collectAsStateWithLifecycle()
            prompt?.let { question ->
                key(question.id) {
                    PromptDialog(question) { answer -> viewModel.answerPrompt(question.id, answer) }
                }
            }
            NewConnectionForm(
                form = form,
                message = message,
                keyDialog = keyDialog,
                errorText = { SshErrorMessages.text(context, it) },
                actions = NewConnectionActions(
                    onName = viewModel::setName,
                    onHost = viewModel::setHost,
                    onPort = viewModel::setPort,
                    onUsername = viewModel::setUsername,
                    onPassword = viewModel::setPassword,
                    onAuthType = viewModel::setAuthType,
                    onJump = viewModel::selectJump,
                    onExpandJump = viewModel::expandJump,
                    onPickKeyFile = { pickKeyFile.launch(arrayOf("*/*")) },
                    onTest = viewModel::test,
                    onGenerateKey = viewModel::generateKey,
                    onSave = viewModel::save,
                    onCancel = onDismiss,
                    onMessageShown = viewModel::onMessageShown,
                    onKeyDialogShown = viewModel::onKeyDialogShown,
                ),
            )
        }
    }
}

private suspend fun readKeyFile(resolver: ContentResolver, uri: Uri, viewModel: NewConnectionViewModel) {
    if (uri.path.orEmpty().lowercase().endsWith(".pub")) viewModel.publicKeyPicked()
    try {
        // A document provider (e.g. a cloud drive) may block, so the read runs off the main thread.
        val content = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readAtMost(MAX_KEY_FILE_BYTES + 1)
                if (bytes.size > MAX_KEY_FILE_BYTES) throw IOException("file is too large for a key")
                String(bytes, Charsets.UTF_8)
            }
        }
        if (content != null) viewModel.setPrivateKey(content)
    } catch (e: IOException) {
        viewModel.keyFileUnreadable(e.message.orEmpty())
    } catch (e: SecurityException) {
        viewModel.keyFileUnreadable(e.message.orEmpty())
    }
}

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(READ_CHUNK)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** A private key is a few KB; a larger file is certainly not one. */
private const val MAX_KEY_FILE_BYTES = 64 * 1024
private const val READ_CHUNK = 8192
