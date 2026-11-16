package hu.billman.konsolessh.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.billman.konsolessh.R

/**
 * Dialogs and toasts over the main screen. A session's question stays pending
 * in the ViewModel, so a recreated activity asks it again.
 */
@Composable
fun MainOverlays(
    state: MainViewModel.UiState,
    overlay: MainOverlayState,
    viewModel: MainViewModel,
    syncViewModel: SyncViewModel,
    onRestore: () -> Unit,
) {
    state.activePrompt?.let { active ->
        key(active.prompt.id) {
            PromptDialog(active.prompt) { answer -> viewModel.answerPrompt(active.tabId, active.prompt.id, answer) }
        }
    }

    Confirmations(state.confirm, viewModel)
    Messages(state.message, state.connectErrors, overlay.toasts, viewModel)
    RestoreOffer(syncViewModel, onRestore)

    UploadStatus(state.upload, overlay.toasts, onShown = viewModel::onUploadShown, onUndo = viewModel::undoUpload)

    overlay.renaming?.let { tab ->
        RenameTabDialog(
            current = tab.title,
            onRename = { viewModel.renameTab(tab.id, it) },
            onDismiss = { overlay.renaming = null },
        )
    }
    if (overlay.pickingLanguage) LanguagePicker(onDismiss = { overlay.pickingLanguage = false })

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ToastHost(overlay.toasts, modifier = Modifier.padding(bottom = TOAST_BOTTOM))
    }
}

@Composable
private fun LanguagePicker(onDismiss: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales()
    LanguageDialog(
        currentTag = if (current.isEmpty) "" else current[0]?.language.orEmpty(),
        onPick = { tag ->
            val locales = if (tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        },
        onDismiss = onDismiss,
    )
}

/** On a first start with nothing saved, offer to restore from Google Drive. */
@Composable
private fun RestoreOffer(syncViewModel: SyncViewModel, onRestore: () -> Unit) {
    val offer by syncViewModel.offerRestore.collectAsStateWithLifecycle()
    if (!offer) return
    AlertDialog(
        onDismissRequest = syncViewModel::dismissOffer,
        title = { Text(stringResource(R.string.sync_offer_title)) },
        text = { Text(stringResource(R.string.sync_offer_text)) },
        confirmButton = {
            TextButton(onClick = {
                syncViewModel.dismissOffer()
                onRestore()
            }) { Text(stringResource(R.string.sync_restore)) }
        },
        dismissButton = {
            TextButton(onClick = syncViewModel::dismissOffer) { Text(stringResource(R.string.sync_offer_later)) }
        },
    )
}

@Composable
private fun Confirmations(confirm: MainViewModel.Confirm?, viewModel: MainViewModel) {
    var shown by remember { mutableStateOf<MainViewModel.Confirm?>(null) }
    LaunchedEffect(confirm) {
        confirm?.let {
            shown = it
            viewModel.onConfirmShown()
        }
    }
    shown?.let { question ->
        val text = when (question) {
            is MainViewModel.Confirm.CloseTab -> stringResource(R.string.close_tab_confirm, question.title)
            is MainViewModel.Confirm.CloseApp ->
                pluralStringResource(R.plurals.close_app_confirm, question.connected, question.connected)
        }
        ConfirmCloseDialog(text, onConfirm = { viewModel.confirmed(question) }, onDismiss = { shown = null })
    }
}

// A failed connect closes its tab after the toast unless the user acts first.
@Composable
private fun Messages(
    message: MainViewModel.Message?,
    errors: List<MainViewModel.ConnectError>,
    toasts: ToastHostState,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val text = when (message) {
        null -> null
        is MainViewModel.Message.Error -> SshErrorMessages.text(context, message.error)
        is MainViewModel.Message.UploadUndone -> stringResource(R.string.upload_undone, message.name)
        is MainViewModel.Message.UndoFailed -> stringResource(R.string.upload_undo_failed, message.reason)
    }
    LaunchedEffect(message) {
        if (text != null) {
            toasts.show(text)
            viewModel.onMessageShown()
        }
    }
    val closeTab = stringResource(R.string.close_tab)
    LaunchedEffect(errors) {
        errors.forEach { failed ->
            viewModel.onErrorShown(failed.tabId)
            toasts.showAutoAction(SshErrorMessages.text(context, failed.error), closeTab, ERROR_TOAST_MS) {
                viewModel.closeTab(failed.tabId)
            }
        }
    }
}

private const val ERROR_TOAST_MS = 5000L
private val TOAST_BOTTOM = 100.dp
