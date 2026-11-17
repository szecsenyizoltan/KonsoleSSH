package hu.billman.konsolessh.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import hu.billman.konsolessh.R
import hu.billman.konsolessh.session.PendingPrompt
import hu.billman.konsolessh.session.PromptAnswer
import hu.billman.konsolessh.ui.theme.KonsoleColors

/** No tap outside and no back press may answer a session's question for the user. */
internal val Unanswerable = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

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
