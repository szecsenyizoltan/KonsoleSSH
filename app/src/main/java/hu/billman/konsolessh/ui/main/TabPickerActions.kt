package hu.billman.konsolessh.ui.main

import hu.billman.konsolessh.model.ConnectionConfig

/** What the user can do in the tab picker. */
data class TabPickerActions(
    val onNewConnection: () -> Unit,
    val onSelectTab: (index: Int) -> Unit,
    val onOpen: (ConnectionConfig) -> Unit,
    val onEdit: (ConnectionConfig) -> Unit,
    val onDelete: (ConnectionConfig) -> Unit,
    val onToggleGroup: (path: String) -> Unit,
    /** Closes the picker; leaving the app is in the main menu. */
    val onBack: () -> Unit,
)

/** What the user picked in the [TabPickerSheet]; each choice also closes it. */
data class TabPickerChoices(
    val onNewConnection: () -> Unit,
    val onSelectTab: (index: Int) -> Unit,
    val onOpen: (ConnectionConfig) -> Unit,
    val onEdit: (ConnectionConfig) -> Unit,
)
