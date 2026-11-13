package hu.billman.konsolessh.ui

import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.TabState

/**
 * The rows of the tab picker, computed without Android so they can be
 * tested and drawn by any UI.
 */
internal object PickerRows {

    data class OpenTab(
        val index: Int,
        val title: String,
        val host: String,
        val status: ConnectionStatus,
        val active: Boolean,
    )

    sealed interface Saved {
        val depth: Int

        /** Connections sharing a name prefix up to an underscore, e.g. "prod_". */
        data class Group(
            val path: String,
            val label: String,
            val count: Int,
            val expanded: Boolean,
            override val depth: Int,
        ) : Saved

        data class Connection(val config: ConnectionConfig, val title: String, override val depth: Int) : Saved
    }

    /** [currentPosition] is the pager position: 0 is the welcome page, tab i is at i + 1. */
    fun openTabs(tabs: List<TabState>, currentPosition: Int): List<OpenTab> = tabs.mapIndexed { index, tab ->
        OpenTab(index, tab.title, tab.host, tab.status, active = index + 1 == currentPosition)
    }

    /**
     * Saved connections as a tree on the underscore-separated parts of their
     * names. A prefix becomes a group only when two or more connections share
     * it; groups come first, then connections, each alphabetically. Children
     * of a group are listed only when its path is in [expanded].
     */
    fun saved(connections: List<ConnectionConfig>, expanded: Set<String>): List<Saved> =
        flatten(tree(connections.map { it to it.displayName() }, ""), expanded, 0)

    private sealed interface Node {
        val label: String

        data class Group(override val label: String, val path: String, val children: List<Node>) : Node
        data class Leaf(val config: ConnectionConfig, override val label: String) : Node
    }

    private fun tree(items: List<Pair<ConnectionConfig, String>>, path: String): List<Node> {
        val byPrefix = items.groupBy { (_, name) -> name.indexOf('_').takeIf { it > 0 }?.let { name.substring(0, it) } }
        val nodes = byPrefix.flatMap { (prefix, group) ->
            if (prefix == null || group.size == 1) {
                group.map { (config, rest) -> Node.Leaf(config, rest) }
            } else {
                val childPath = "$path${prefix}_"
                val children = group.map { (config, name) -> config to name.substring(prefix.length + 1) }
                listOf(Node.Group("${prefix}_", childPath, tree(children, childPath)))
            }
        }
        return nodes.sortedWith(compareBy<Node> { it is Node.Leaf }.thenBy { it.label.lowercase() })
    }

    private fun flatten(nodes: List<Node>, expanded: Set<String>, depth: Int): List<Saved> = nodes.flatMap { node ->
        when (node) {
            is Node.Leaf -> {
                val title = node.label.ifBlank { node.config.displayName() }
                listOf(Saved.Connection(node.config, title, depth))
            }
            is Node.Group -> {
                val open = node.path in expanded
                val row = Saved.Group(node.path, node.label.trimEnd('_'), leaves(node), open, depth)
                listOf(row) + if (open) flatten(node.children, expanded, depth + 1) else emptyList()
            }
        }
    }

    private fun leaves(node: Node): Int = when (node) {
        is Node.Leaf -> 1
        is Node.Group -> node.children.sumOf(::leaves)
    }
}
