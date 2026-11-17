package hu.billman.konsolessh.ui.main

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.ui.common.ToastHostState

/**
 * What the main screen's own menus opened and its toasts: state that belongs
 * to the screen, not to a session, so it is not in [MainViewModel.UiState].
 */
@Stable
class MainOverlayState {
    val toasts = ToastHostState()

    /** The tab whose rename dialog is open. */
    var renaming by mutableStateOf<TabState?>(null)
    var pickingLanguage by mutableStateOf(false)
}
