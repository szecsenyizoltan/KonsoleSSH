package hu.billman.konsolessh.ui

import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.billman.konsolessh.R
import hu.billman.konsolessh.ui.common.rememberDialogScope
import hu.billman.konsolessh.ui.common.swipeToDismissHorizontally
import hu.billman.konsolessh.ui.theme.KonsoleColors
import kotlinx.coroutines.launch

/**
 * The open tabs and the saved connections in a full-height bottom sheet.
 * The saved connections are loaded anew at each opening: their ViewModel
 * is scoped to the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabPickerSheet(
    mainViewModel: MainViewModel,
    currentPage: Int,
    choices: TabPickerChoices,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val connections = viewModel<ConnectionsViewModel>(
        viewModelStoreOwner = rememberDialogScope("tab_picker"),
        factory = KonsoleViewModelFactory.from(context),
    )
    val main by mainViewModel.state.collectAsStateWithLifecycle()
    val sessions = main.sessions
    val expanded = main.pickerExpanded
    val state by connections.state.collectAsStateWithLifecycle()

    // The sheet covers the screen's own toasts, so the platform toast is used.
    val storeError = stringResource(R.string.err_store_unreadable)
    LaunchedEffect(state.storeError) {
        if (state.storeError) Toast.makeText(context, storeError, Toast.LENGTH_LONG).show()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The sheet slides away before the choice runs and the sheet leaves the composition.
    val close: (() -> Unit) -> Unit = { then ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    // Closed by a sideways swipe, the back row or the system back; not by dragging down.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = KonsoleColors.Surface,
        dragHandle = null,
        modifier = Modifier.swipeToDismissHorizontally(onDismiss),
    ) {
        TabPickerContent(
            openTabs = PickerRows.openTabs(sessions.tabs, currentPage),
            saved = PickerRows.saved(state.connections, expanded),
            actions = TabPickerActions(
                onNewConnection = { close(choices.onNewConnection) },
                onSelectTab = { index -> close { choices.onSelectTab(index) } },
                onOpen = { connection -> close { choices.onOpen(connection) } },
                onEdit = { connection -> close { choices.onEdit(connection) } },
                onDelete = { connections.delete(it.id) },
                onToggleGroup = mainViewModel::togglePickerGroup,
                onBack = { close {} },
            ),
        )
    }
}
