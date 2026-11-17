package hu.billman.konsolessh.ui.sync

/** What the user can do on the sync screen. */
data class SyncActions(
    val onBack: () -> Unit,
    val onConnect: () -> Unit,
    val onEnable: (passphrase: String, repeat: String?) -> Unit,
    val onSyncNow: () -> Unit,
    val onDisable: () -> Unit,
    val onGrant: () -> Unit,
)
