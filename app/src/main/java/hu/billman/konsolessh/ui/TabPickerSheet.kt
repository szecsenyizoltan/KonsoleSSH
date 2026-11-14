package hu.billman.konsolessh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import hu.billman.konsolessh.R
import hu.billman.konsolessh.ui.theme.KonsoleTheme

class TabPickerSheet : BottomSheetDialogFragment() {

    /**
     * A választás a Fragment Result API-n megy vissza ([RESULT_KEY]), így
     * forgatás után is eljut az Activityhez; listener-referencia nincs.
     */
    enum class Action { SELECT_TAB, OPEN_CONNECTION, NEW_CONNECTION, EDIT_CONNECTION, CLOSE_APP }

    companion object {
        const val RESULT_KEY = "tab_picker_result"
        const val KEY_ACTION = "action"
        const val KEY_POSITION = "position"
        const val KEY_CONNECTION_ID = "connection_id"
        private const val ARG_CURRENT_POSITION = "current_position"

        fun newInstance(currentPosition: Int) =
            TabPickerSheet().apply { arguments = bundleOf(ARG_CURRENT_POSITION to currentPosition) }
    }

    private val mainViewModel: MainViewModel by activityViewModels { KonsoleViewModelFactory.from(requireContext()) }
    private val connectionsViewModel: ConnectionsViewModel by viewModels {
        KonsoleViewModelFactory.from(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                KonsoleTheme {
                    val sessions by mainViewModel.sessionsState.collectAsStateWithLifecycle()
                    val expanded by mainViewModel.pickerExpanded.collectAsStateWithLifecycle()
                    val state by connectionsViewModel.state.collectAsStateWithLifecycle()
                    val current = requireArguments().getInt(ARG_CURRENT_POSITION)
                    val storeError = stringResource(R.string.err_store_unreadable)
                    LaunchedEffect(state.storeError) {
                        if (state.storeError) Toast.makeText(requireContext(), storeError, Toast.LENGTH_LONG).show()
                    }
                    TabPickerContent(
                        openTabs = PickerRows.openTabs(sessions.tabs, current),
                        saved = PickerRows.saved(state.connections, expanded),
                        actions = TabPickerActions(
                            onNewConnection = { finish(Action.NEW_CONNECTION) },
                            onSelectTab = { finish(Action.SELECT_TAB, bundleOf(KEY_POSITION to it)) },
                            onOpen = { finish(Action.OPEN_CONNECTION, bundleOf(KEY_CONNECTION_ID to it.id)) },
                            onEdit = { finish(Action.EDIT_CONNECTION, bundleOf(KEY_CONNECTION_ID to it.id)) },
                            onDelete = { connectionsViewModel.delete(it.id) },
                            onToggleGroup = mainViewModel::togglePickerGroup,
                            onExit = { finish(Action.CLOSE_APP) },
                        ),
                    )
                }
            }
        }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    private fun finish(action: Action, extras: Bundle = Bundle()) {
        extras.putString(KEY_ACTION, action.name)
        setFragmentResult(RESULT_KEY, extras)
        dismiss()
    }
}
