package hu.billman.konsolessh.ui

import hu.billman.konsolessh.model.ConnectionConfig.AuthType

/** Everything the form can report; the ViewModel implements the behaviour. */
data class NewConnectionActions(
    val onName: (String) -> Unit,
    val onHost: (String) -> Unit,
    val onPort: (String) -> Unit,
    val onUsername: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onAuthType: (AuthType) -> Unit,
    val onJump: (String?) -> Unit,
    val onExpandJump: () -> Unit,
    val onPickKeyFile: () -> Unit,
    val onTest: () -> Unit,
    val onGenerateKey: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
    val onMessageShown: () -> Unit,
    val onKeyDialogShown: () -> Unit,
)
