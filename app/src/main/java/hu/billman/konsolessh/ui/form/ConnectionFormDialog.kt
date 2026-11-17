package hu.billman.konsolessh.ui.form

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.billman.konsolessh.ui.KonsoleViewModelFactory
import hu.billman.konsolessh.ui.common.PromptDialog
import hu.billman.konsolessh.ui.common.SshErrorMessages
import hu.billman.konsolessh.ui.common.rememberDialogScope

/**
 * The connection form in a dialog. The form's state and rules live in
 * [NewConnectionViewModel], scoped to this dialog; this function only opens
 * the picked key file and reports the saved connection's id through [onSaved].
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

    // The application's resolver: the ViewModel may still read after the activity is recreated.
    val resolver = context.applicationContext.contentResolver
    val pickKeyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadKeyFile(uri.path.orEmpty()) { resolver.openInputStream(uri) }
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
