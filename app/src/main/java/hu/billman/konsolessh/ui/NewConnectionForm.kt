package hu.billman.konsolessh.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.billman.konsolessh.R
import hu.billman.konsolessh.model.ConnectionConfig.AuthType
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ui.NewConnectionViewModel.FormState
import hu.billman.konsolessh.ui.NewConnectionViewModel.InvalidReason
import hu.billman.konsolessh.ui.NewConnectionViewModel.KeyDialog
import hu.billman.konsolessh.ui.NewConnectionViewModel.Message
import hu.billman.konsolessh.ui.NewConnectionViewModel.Status
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.Monospace
import kotlinx.coroutines.launch

/**
 * The new / edit connection form as a dialog card: fields, authentication,
 * connection test, key generation, jump host, and the notices and key
 * dialogs that come with them. Stateless; [form] is drawn as it is.
 */
@Composable
fun NewConnectionForm(
    form: FormState,
    message: Message?,
    keyDialog: KeyDialog?,
    errorText: (SshError) -> String,
    actions: NewConnectionActions,
) {
    val snackbar = remember { SnackbarHostState() }
    val messageText = message?.let { messageText(it, errorText) }
    LaunchedEffect(message) {
        if (messageText != null) {
            actions.onMessageShown()
            snackbar.showSnackbar(messageText)
        }
    }
    var shownKeyDialog by remember { mutableStateOf<KeyDialog?>(null) }
    LaunchedEffect(keyDialog) {
        if (keyDialog != null) {
            shownKeyDialog = keyDialog
            actions.onKeyDialogShown()
        }
    }

    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
        Surface(color = KonsoleColors.Surface, shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(
                        if (form.editing) R.string.dialog_edit_connection_title else R.string.dialog_new_connection_title,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Fields(form, actions)
                    Authentication(form, actions)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = actions.onTest, enabled = !form.busy) {
                            Text(stringResource(R.string.action_test_connection), fontSize = 13.sp)
                        }
                        Text(
                            text = statusText(form.status, errorText),
                            fontSize = 12.sp,
                            color = KonsoleColors.TextSecondary,
                            maxLines = 2,
                            modifier = Modifier.testTag(STATUS_TAG),
                        )
                    }
                    JumpHost(form, actions)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = actions.onSave) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.key_copied)
    shownKeyDialog?.let { dialog ->
        KeyDialogView(
            dialog = dialog,
            onClose = { shownKeyDialog = null },
            onCopied = { scope.launch { snackbar.showSnackbar(copied) } },
        )
    }
}

@Composable
private fun Fields(form: FormState, actions: NewConnectionActions) {
    FormTextField(
        value = form.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.connection_name_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
    FormTextField(
        value = form.host,
        onValueChange = actions.onHost,
        label = { Text(stringResource(if (form.isInternalHost) R.string.host_internal_hint else R.string.host_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(
            value = form.port,
            onValueChange = actions.onPort,
            label = {
                Text(stringResource(if (form.isInternalHost) R.string.port_internal_hint else R.string.port_hint))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        FormTextField(
            value = form.username,
            onValueChange = actions.onUsername,
            label = { Text(stringResource(R.string.username_hint)) },
            modifier = Modifier.weight(2f),
        )
    }
}

@Composable
private fun Authentication(form: FormState, actions: NewConnectionActions) {
    Row(Modifier.selectableGroup()) {
        AUTH_OPTIONS.forEach { (type, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .selectable(form.authType == type, role = Role.RadioButton) { actions.onAuthType(type) }
                    .padding(end = 16.dp),
            ) {
                RadioButton(selected = form.authType == type, onClick = null)
                Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (form.usesKey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onPickKeyFile) {
                Text(stringResource(R.string.pick_key_file), fontSize = 13.sp)
            }
            TextButton(onClick = actions.onGenerateKey, enabled = form.canGenerateKey) {
                Text(stringResource(R.string.generate_and_upload_key), fontSize = 13.sp)
            }
            if (form.hasKey) Text(stringResource(R.string.sym_check), fontSize = 18.sp, color = KonsoleColors.Success)
        }
    } else {
        var visible by rememberSaveable { mutableStateOf(false) }
        FormTextField(
            value = form.password,
            onValueChange = actions.onPassword,
            label = { Text(stringResource(R.string.password_hint_optional)) },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        painter = painterResource(
                            if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                        ),
                        contentDescription = stringResource(
                            if (visible) R.string.content_desc_hide_password else R.string.content_desc_show_password,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpHost(form: FormState, actions: NewConnectionActions) {
    if (!form.showJump) {
        TextButton(onClick = actions.onExpandJump) {
            Text(stringResource(R.string.configure_jump_host), fontSize = 13.sp)
        }
        return
    }
    HorizontalDivider(color = KonsoleColors.TextSecondary)
    Text(
        text = stringResource(R.string.jump_host_section_title),
        color = KonsoleColors.Accent,
        style = MaterialTheme.typography.titleSmall,
    )
    Text(stringResource(R.string.jump_host_pick_hint), color = KonsoleColors.TextSecondary, fontSize = 13.sp)
    if (form.jumpOptions.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        val none = stringResource(R.string.jump_host_none)
        val selected = form.jumpOptions.find { it.id == form.jumpId }?.label ?: none
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(none) }, onClick = {
                    actions.onJump(null)
                    expanded = false
                })
                form.jumpOptions.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label, fontFamily = Monospace) }, onClick = {
                        actions.onJump(option.id)
                        expanded = false
                    })
                }
            }
        }
    }
    HorizontalDivider(color = KonsoleColors.TextSecondary)
}

@Composable
private fun KeyDialogView(dialog: KeyDialog, onClose: () -> Unit, onCopied: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    when (dialog) {
        is KeyDialog.Uploaded -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.key_generated_title)) },
            text = { Text(stringResource(R.string.upload_success_message, dialog.publicLine)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) } },
        )
        is KeyDialog.ManualFallback -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.upload_manual_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.upload_manual_message))
                    SelectionContainer {
                        Text(
                            text = dialog.publicLine,
                            fontFamily = Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ssh-public-key", dialog.publicLine)))
                        onClose()
                        onCopied()
                    }
                }) { Text(stringResource(R.string.action_copy_public_key)) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun statusText(status: Status, errorText: (SshError) -> String): String = when (status) {
    Status.None -> ""
    Status.Testing -> stringResource(R.string.test_running)
    Status.Uploading -> stringResource(R.string.upload_running)
    Status.StoreUnreadable -> errorText(SshError.StoreUnreadable)
    is Status.TestSucceeded -> stringResource(R.string.test_success, status.serverVersion.ifBlank { "?" })
    is Status.TestFailed -> stringResource(R.string.test_failure, errorText(status.error))
    is Status.Invalid -> stringResource(
        when (status.reason) {
            InvalidReason.NEEDS_HOST_AND_USER -> R.string.test_needs_host_user
            InvalidReason.NEEDS_PRIVATE_KEY -> R.string.test_needs_private_key
            InvalidReason.NEEDS_PASSWORD -> R.string.test_needs_password
        },
    )
    is Status.UploadSucceeded -> stringResource(R.string.upload_success, status.serverType.name.lowercase())
    is Status.UploadFailed -> stringResource(R.string.upload_failure, errorText(status.error))
}

@Composable
private fun messageText(message: Message, errorText: (SshError) -> String): String = when (message) {
    Message.HostRequired -> stringResource(R.string.error_host_required)
    Message.UsernameRequired -> stringResource(R.string.error_username_required)
    Message.StoreWriteFailed -> stringResource(R.string.err_store_write)
    Message.NeedsTest -> stringResource(R.string.generate_needs_test)
    is Message.KeyGenerationFailed -> stringResource(R.string.error_generic, errorText(message.error))
    is Message.KeyFileUnreadable -> stringResource(R.string.error_read_file, message.reason)
    Message.PublicKeyPicked -> stringResource(R.string.warning_public_key)
}

private val AUTH_OPTIONS = listOf(AuthType.PASSWORD to R.string.password, AuthType.PRIVATE_KEY to R.string.private_key)

internal const val STATUS_TAG = "connection_test_status"
