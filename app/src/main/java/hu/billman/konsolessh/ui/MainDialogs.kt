package hu.billman.konsolessh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import hu.billman.konsolessh.R
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.ui.theme.KonsoleColors
import java.util.Locale

/** No tap outside and no back press may answer a session's question for the user. */
private val Unanswerable = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

/**
 * A session's question: a password, or whether to trust a host key. Only
 * the buttons answer; if the screen goes away, the question stays pending
 * and is asked again.
 */
@Composable
fun PromptDialog(prompt: PendingPrompt, onAnswer: (PromptAnswer) -> Unit) {
    when (prompt) {
        is PendingPrompt.Password -> PasswordPrompt(prompt) { onAnswer(PromptAnswer.Password(it)) }
        is PendingPrompt.HostKey -> HostKeyPrompt(prompt) { onAnswer(PromptAnswer.HostKey(it)) }
    }
}

@Composable
private fun PasswordPrompt(prompt: PendingPrompt.Password, onAnswer: (String?) -> Unit) {
    var password by rememberSaveable(prompt.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        properties = Unanswerable,
        title = { Text(prompt.displayHost) },
        text = {
            Column {
                Text(stringResource(R.string.password_prompt_message))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(password) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// A changed key may mean a man-in-the-middle attack, so there cancelling is the main button.
@Composable
private fun HostKeyPrompt(prompt: PendingPrompt.HostKey, onAnswer: (Boolean) -> Unit) {
    val key = prompt.prompt
    val accept = @Composable {
        TextButton(onClick = { onAnswer(true) }) {
            Text(stringResource(if (key.changed) R.string.hostkey_replace else R.string.hostkey_accept))
        }
    }
    val cancel = @Composable {
        TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.action_cancel)) }
    }
    AlertDialog(
        onDismissRequest = {},
        properties = Unanswerable,
        title = {
            Text(
                text = stringResource(
                    if (key.changed) R.string.hostkey_changed_title else R.string.hostkey_unknown_title,
                ),
                color = if (key.changed) KonsoleColors.Error else KonsoleColors.TextPrimary,
            )
        },
        text = {
            Text(
                stringResource(
                    if (key.changed) R.string.hostkey_changed_message else R.string.hostkey_unknown_message,
                    key.host,
                    key.keyType,
                    key.fingerprint,
                ),
            )
        },
        confirmButton = if (key.changed) cancel else accept,
        dismissButton = if (key.changed) accept else cancel,
    )
}

/** Asks before closing something; [text] says what. */
@Composable
fun ConfirmCloseDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Renames a tab; the current name starts selected, so typing replaces it. */
@Composable
fun RenameTabDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(current, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(current, selection = TextRange(0, current.length)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_rename_tab_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tab_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onRename(name.text)
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The app's languages by tag; an empty tag means the system's language. */
val AppLanguages = listOf("", "en", "hu", "de", "es", "fr", "sk", "ro")

/** Picks the in-app language; each language is named in itself. */
@Composable
fun LanguageDialog(currentTag: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_language)) },
        text = {
            Column {
                AppLanguages.forEach { tag ->
                    val label = if (tag.isEmpty()) {
                        stringResource(R.string.language_system_default)
                    } else {
                        Locale.forLanguageTag(tag).let { it.getDisplayName(it) }.replaceFirstChar { it.titlecase() }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(tag == currentTag, role = Role.RadioButton) {
                                onDismiss()
                                onPick(tag)
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = tag == currentTag, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Progress of a running upload; [progress] is null while the size is unknown. */
@Composable
fun UploadProgressDialog(name: String, status: String, progress: Float?) {
    AlertDialog(
        onDismissRequest = {},
        properties = Unanswerable,
        title = { Text(stringResource(R.string.upload_title, name)) },
        text = {
            Column {
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(status, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
    )
}
