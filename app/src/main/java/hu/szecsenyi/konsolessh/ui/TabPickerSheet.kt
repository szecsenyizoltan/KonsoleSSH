package hu.szecsenyi.konsolessh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.DialogTabPickerBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections

class TabPickerSheet : BottomSheetDialogFragment() {

    data class TabEntry(
        val title: String,
        val isActive: Boolean,
        val host: String?,          // null for welcome/local tabs
        val status: ConnectionStatus = ConnectionStatus.NONE
    )

    interface Listener {
        fun onTabSelected(position: Int)
        fun onSavedConnectionSelected(config: ConnectionConfig)
        fun onNewConnectionRequested()
        fun onEditConnectionRequested(config: ConnectionConfig)
        fun onDeleteConnectionRequested(config: ConnectionConfig)
        fun onCloseAppRequested()
    }

    /** Persist expanded groups across reopens of the sheet. */
    companion object {
        private val expandedPaths: MutableSet<String> = mutableSetOf()
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

    var listener: Listener? = null
    var openTabs: List<TabEntry> = emptyList()

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
        buildSavedConnections()
        binding.btnNewConnection.setOnClickListener {
            dismiss()
            listener?.onNewConnectionRequested()
        }
        binding.btnCloseApp.setOnClickListener {
            dismiss()
            listener?.onCloseAppRequested()
        }
    }

    private fun buildOpenTabs() {
        if (openTabs.isEmpty()) {
            binding.labelOpenTabs.visibility = View.GONE
            return
        }
        openTabs.forEachIndexed { index, entry ->
            val row = inflateRow()
            val icon = row.findViewById<TextView>(R.id.itemIcon)
            icon.text = if (entry.isActive) "▶" else "●"
            icon.setTextColor(when (entry.status) {
                ConnectionStatus.CONNECTED    -> android.graphics.Color.rgb(63, 185, 80)
                ConnectionStatus.CONNECTING   -> android.graphics.Color.rgb(240, 180, 0)
                ConnectionStatus.DISCONNECTED -> android.graphics.Color.rgb(248, 81, 73)
                ConnectionStatus.NONE         -> android.graphics.Color.GRAY
            })
            row.findViewById<TextView>(R.id.itemTitle).text = entry.title

            // Show hostname below title if available
            if (!entry.host.isNullOrBlank()) {
                row.findViewById<TextView>(R.id.itemSubtitle).apply {
                    text = entry.host
                    visibility = View.VISIBLE
                }
            }

            if (entry.isActive) {
                row.findViewById<TextView>(R.id.itemBadge).apply {
                    text = getString(R.string.tab_active)
                    setTextColor(android.graphics.Color.WHITE)
                    visibility = View.VISIBLE
                }
            }

            row.setOnClickListener {
                dismiss()
                listener?.onTabSelected(index)
            }
            binding.containerTabs.addView(row)
        }
    }

    private fun buildSavedConnections() {
        binding.containerSaved.removeAllViews()
        val saved = SavedConnections.load(requireContext())
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
            setOnClickListener {
                dismiss()
                listener?.onEditConnectionRequested(config)
            }
        }
        row.findViewById<ImageButton>(R.id.btnDeleteRow).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(
                    requireContext(), hu.szecsenyi.konsolessh.R.style.KonsoleDialog
                )
                    .setMessage(getString(R.string.delete_connection_confirm, config.displayName()))
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        SavedConnections.delete(requireContext(), config.id)
                        listener?.onDeleteConnectionRequested(config)
                        buildSavedConnections()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
        row.setOnClickListener {
            dismiss()
            listener?.onSavedConnectionSelected(config)
        }
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
