package hu.billman.konsolessh.ui

import hu.billman.konsolessh.session.TabState

/** What the main screen's bars report. */
data class MainScreenActions(
    val onSelectPage: (Int) -> Unit,
    val onCloseTab: (String) -> Unit,
    val onRenameTab: (TabState) -> Unit,
    val onNewTab: () -> Unit,
    val onZoom: (Float) -> Unit,
    val onCloseCurrentTab: () -> Unit,
    val onManageConnections: () -> Unit,
    val onSync: () -> Unit,
    val onLanguage: () -> Unit,
    val onPrivacyPolicy: () -> Unit,
    val onExit: () -> Unit,
)
