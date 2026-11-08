package hu.billman.konsolessh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.DialogTabPickerBinding
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.ConnectionStatus
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

        /** Expanded groups survive reopening the sheet (process lifetime only). */
        private val expandedPaths: MutableSet<String> = mutableSetOf()

        fun newInstance(currentPosition: Int) =
            TabPickerSheet().apply { arguments = bundleOf(ARG_CURRENT_POSITION to currentPosition) }
    }

    private sealed class TreeNode {
        data class Group(
            val label: String,         // e.g. "cc_"
            val fullPath: String,      // e.g. "cc_desktop_"
            val children: List<TreeNode>
        ) : TreeNode()
        data class Leaf(
            val config: ConnectionConfig,
            val label: String          // remaining name after stripping parent prefixes
        ) : TreeNode()
    }

    private val mainViewModel: MainViewModel by activityViewModels { KonsoleViewModelFactory.from(requireContext()) }
    private val connectionsViewModel: ConnectionsViewModel by viewModels {
        KonsoleViewModelFactory.from(requireContext())
    }

    private var saved: List<ConnectionConfig> = emptyList()

    private var _binding: DialogTabPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogTabPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            it.layoutParams.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildOpenTabs()
        binding.btnNewConnection.setOnClickListener { finish(Action.NEW_CONNECTION) }
        binding.btnCloseApp.setOnClickListener { finish(Action.CLOSE_APP) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                connectionsViewModel.state.collect { state ->
                    saved = state.connections
                    buildSavedConnections()
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

    private fun buildOpenTabs() {
        val current = requireArguments().getInt(ARG_CURRENT_POSITION)
        val openTabs = mainViewModel.sessionsState.value.tabs
        if (openTabs.isEmpty()) {
            binding.labelOpenTabs.visibility = View.GONE
            return
        }
        openTabs.forEachIndexed { index, entry ->
            val isActive = index + 1 == current
            val row = inflateRow()
            val icon = row.findViewById<TextView>(R.id.itemIcon)
            icon.text = if (isActive) "▶" else "●"
            icon.setTextColor(when (entry.status) {
                ConnectionStatus.CONNECTED    -> android.graphics.Color.rgb(63, 185, 80)
                ConnectionStatus.CONNECTING   -> android.graphics.Color.rgb(240, 180, 0)
                ConnectionStatus.DISCONNECTED -> android.graphics.Color.rgb(248, 81, 73)
                ConnectionStatus.NONE         -> android.graphics.Color.GRAY
            })
            row.findViewById<TextView>(R.id.itemTitle).text = entry.title

            // Show hostname below title if available
            if (entry.host.isNotBlank()) {
                row.findViewById<TextView>(R.id.itemSubtitle).apply {
                    text = entry.host
                    visibility = View.VISIBLE
                }
            }

            if (isActive) {
                row.findViewById<TextView>(R.id.itemBadge).apply {
                    text = getString(R.string.tab_active)
                    setTextColor(android.graphics.Color.WHITE)
                    visibility = View.VISIBLE
                }
            }

            row.setOnClickListener { finish(Action.SELECT_TAB, bundleOf(KEY_POSITION to index)) }
            binding.containerTabs.addView(row)
        }
    }

    private fun buildSavedConnections() {
        binding.containerSaved.removeAllViews()
        if (saved.isEmpty()) {
            binding.labelSaved.visibility = View.GONE
            return
        }
        binding.labelSaved.visibility = View.VISIBLE
        val tree = buildTree(saved.map { it to it.displayName() }, "")
        renderNodes(tree, 0)
    }

    private fun buildTree(
        items: List<Pair<ConnectionConfig, String>>,
        currentPath: String
    ): List<TreeNode> {
        // Group by first token before '_'; null means "no underscore left".
        val grouped = items.groupBy { (_, name) ->
            val idx = name.indexOf('_')
            if (idx <= 0) null else name.substring(0, idx)
        }
        val result = mutableListOf<TreeNode>()
        grouped.forEach { (prefix, group) ->
            if (prefix == null || group.size == 1) {
                group.forEach { (cfg, remaining) ->
                    result.add(TreeNode.Leaf(cfg, remaining))
                }
            } else {
                val childItems = group.map { (cfg, name) ->
                    cfg to name.substring(prefix.length + 1)
                }
                val newPath = "${currentPath}${prefix}_"
                result.add(
                    TreeNode.Group(
                        label = "${prefix}_",
                        fullPath = newPath,
                        children = buildTree(childItems, newPath)
                    )
                )
            }
        }
        // Groups first, then leaves; inside each kind sort alphabetically.
        return result.sortedWith(
            compareBy<TreeNode> { it is TreeNode.Leaf }.thenBy {
                when (it) {
                    is TreeNode.Group -> it.label.lowercase()
                    is TreeNode.Leaf  -> it.label.lowercase()
                }
            }
        )
    }

    private fun renderNodes(nodes: List<TreeNode>, depth: Int) {
        nodes.forEach { node ->
            when (node) {
                is TreeNode.Group -> renderGroupRow(node, depth)
                is TreeNode.Leaf  -> renderLeafRow(node, depth)
            }
        }
    }

    private fun renderGroupRow(group: TreeNode.Group, depth: Int) {
        val row = inflateRow()
        applyDepthPadding(row, depth)
        val expanded = expandedPaths.contains(group.fullPath)
        row.findViewById<TextView>(R.id.itemIcon).text = if (expanded) "📂" else "📁"
        row.findViewById<TextView>(R.id.itemTitle).text = group.label.trimEnd('_')
        row.findViewById<TextView>(R.id.itemSubtitle).apply {
            text = getString(R.string.group_n_connections, countLeaves(group))
            visibility = View.VISIBLE
        }
        row.findViewById<TextView>(R.id.itemBadge).apply {
            text = if (expanded) "▼" else "▶"
            setTextColor(android.graphics.Color.WHITE)
            visibility = View.VISIBLE
        }
        row.setOnClickListener {
            if (!expandedPaths.add(group.fullPath)) {
                expandedPaths.remove(group.fullPath)
            }
            buildSavedConnections()
        }
        binding.containerSaved.addView(row)

        if (expanded) renderNodes(group.children, depth + 1)
    }

    private fun renderLeafRow(leaf: TreeNode.Leaf, depth: Int) {
        val config = leaf.config
        val row = inflateRow()
        applyDepthPadding(row, depth)
        row.findViewById<TextView>(R.id.itemIcon).text = "⚡"
        row.findViewById<TextView>(R.id.itemTitle).text =
            leaf.label.ifBlank { config.displayName() }
        row.findViewById<TextView>(R.id.itemSubtitle).apply {
            text = "${config.username}@${config.host}:${config.port}"
            visibility = View.VISIBLE
        }
        row.findViewById<ImageButton>(R.id.btnEditRow).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish(Action.EDIT_CONNECTION, bundleOf(KEY_CONNECTION_ID to config.id)) }
        }
        row.findViewById<ImageButton>(R.id.btnDeleteRow).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(
                    requireContext(),
                    R.style.KonsoleDialog,
                )
                    .setMessage(getString(R.string.delete_connection_confirm, config.displayName()))
                    .setPositiveButton(R.string.action_delete) { _, _ -> connectionsViewModel.delete(config.id) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
        row.setOnClickListener { finish(Action.OPEN_CONNECTION, bundleOf(KEY_CONNECTION_ID to config.id)) }
        binding.containerSaved.addView(row)
    }

    private fun countLeaves(node: TreeNode): Int = when (node) {
        is TreeNode.Leaf  -> 1
        is TreeNode.Group -> node.children.sumOf { countLeaves(it) }
    }

    private fun applyDepthPadding(row: View, depth: Int) {
        val density = resources.displayMetrics.density
        val basePad = (16 * density).toInt()
        val stepPad = (20 * density).toInt()
        row.setPadding(basePad + depth * stepPad, 0, basePad, 0)
    }

    private fun inflateRow(): View {
        val parent = binding.containerTabs.parent as? ViewGroup ?: binding.containerTabs
        return LayoutInflater.from(requireContext())
            .inflate(R.layout.item_picker_row, parent, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
