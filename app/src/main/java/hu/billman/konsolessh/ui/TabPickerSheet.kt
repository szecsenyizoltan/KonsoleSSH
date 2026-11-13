package hu.billman.konsolessh.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.DialogTabPickerBinding
import hu.billman.konsolessh.databinding.ItemPickerRowBinding
import hu.billman.konsolessh.session.ConnectionStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
        private const val ROW_PADDING_DP = 16
        private const val DEPTH_STEP_DP = 20

        fun newInstance(currentPosition: Int) =
            TabPickerSheet().apply { arguments = bundleOf(ARG_CURRENT_POSITION to currentPosition) }
    }

    private val mainViewModel: MainViewModel by activityViewModels { KonsoleViewModelFactory.from(requireContext()) }
    private val connectionsViewModel: ConnectionsViewModel by viewModels {
        KonsoleViewModelFactory.from(requireContext())
    }

    private var _binding: DialogTabPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogTabPickerBinding.inflate(inflater, container, false)
        return binding.root
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val current = requireArguments().getInt(ARG_CURRENT_POSITION)
        renderOpenTabs(PickerRows.openTabs(mainViewModel.sessionsState.value.tabs, current))
        binding.btnNewConnection.setOnClickListener { finish(Action.NEW_CONNECTION) }
        binding.btnCloseApp.setOnClickListener { finish(Action.CLOSE_APP) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(connectionsViewModel.state, mainViewModel.pickerExpanded, ::Pair).collect { (state, expanded) ->
                    renderSaved(PickerRows.saved(state.connections, expanded))
                    if (state.storeError) KonsoleToast.show(binding.root, getString(R.string.err_store_unreadable))
                }
            }
        }
    }

    private fun finish(action: Action, extras: Bundle = Bundle()) {
        extras.putString(KEY_ACTION, action.name)
        setFragmentResult(RESULT_KEY, extras)
        dismiss()
    }

    private fun renderOpenTabs(tabs: List<PickerRows.OpenTab>) {
        binding.labelOpenTabs.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        tabs.forEach { tab ->
            val row = ItemPickerRowBinding.inflate(layoutInflater, binding.containerTabs, false)
            row.itemIcon.text = if (tab.active) "▶" else "●"
            row.itemIcon.setTextColor(statusColor(tab.status))
            row.itemTitle.text = tab.title
            if (tab.host.isNotBlank()) {
                row.itemSubtitle.text = tab.host
                row.itemSubtitle.visibility = View.VISIBLE
            }
            if (tab.active) {
                row.itemBadge.text = getString(R.string.tab_active)
                row.itemBadge.setTextColor(Color.WHITE)
                row.itemBadge.visibility = View.VISIBLE
            }
            row.root.setOnClickListener { finish(Action.SELECT_TAB, bundleOf(KEY_POSITION to tab.index)) }
            binding.containerTabs.addView(row.root)
        }
    }

    private fun statusColor(status: ConnectionStatus): Int = when (status) {
        ConnectionStatus.CONNECTED -> Color.rgb(63, 185, 80)
        ConnectionStatus.CONNECTING -> Color.rgb(240, 180, 0)
        ConnectionStatus.DISCONNECTED -> Color.rgb(248, 81, 73)
        ConnectionStatus.NONE -> Color.GRAY
    }

    private fun renderSaved(rows: List<PickerRows.Saved>) {
        binding.containerSaved.removeAllViews()
        binding.labelSaved.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        rows.forEach { row ->
            val view = when (row) {
                is PickerRows.Saved.Group -> groupRow(row)
                is PickerRows.Saved.Connection -> connectionRow(row)
            }
            val density = resources.displayMetrics.density
            val base = (ROW_PADDING_DP * density).toInt()
            view.setPadding(base + row.depth * (DEPTH_STEP_DP * density).toInt(), 0, base, 0)
            binding.containerSaved.addView(view)
        }
    }

    private fun groupRow(group: PickerRows.Saved.Group): View {
        val row = ItemPickerRowBinding.inflate(layoutInflater, binding.containerSaved, false)
        row.itemIcon.text = if (group.expanded) "📂" else "📁"
        row.itemTitle.text = group.label
        row.itemSubtitle.text = getString(R.string.group_n_connections, group.count)
        row.itemSubtitle.visibility = View.VISIBLE
        row.itemBadge.text = if (group.expanded) "▼" else "▶"
        row.itemBadge.setTextColor(Color.WHITE)
        row.itemBadge.visibility = View.VISIBLE
        row.root.setOnClickListener { mainViewModel.togglePickerGroup(group.path) }
        return row.root
    }

    private fun connectionRow(item: PickerRows.Saved.Connection): View {
        val config = item.config
        val row = ItemPickerRowBinding.inflate(layoutInflater, binding.containerSaved, false)
        row.itemIcon.text = "⚡"
        row.itemTitle.text = item.title
        row.itemSubtitle.text = "${config.username}@${config.host}:${config.port}"
        row.itemSubtitle.visibility = View.VISIBLE
        row.btnEditRow.visibility = View.VISIBLE
        row.btnEditRow.setOnClickListener {
            finish(Action.EDIT_CONNECTION, bundleOf(KEY_CONNECTION_ID to config.id))
        }
        row.btnDeleteRow.visibility = View.VISIBLE
        row.btnDeleteRow.setOnClickListener {
            AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
                .setMessage(getString(R.string.delete_connection_confirm, config.displayName()))
                .setPositiveButton(R.string.action_delete) { _, _ -> connectionsViewModel.delete(config.id) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        row.root.setOnClickListener { finish(Action.OPEN_CONNECTION, bundleOf(KEY_CONNECTION_ID to config.id)) }
        return row.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
