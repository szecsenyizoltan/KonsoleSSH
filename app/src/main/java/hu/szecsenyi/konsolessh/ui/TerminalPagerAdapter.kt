package hu.szecsenyi.konsolessh.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import java.util.UUID

data class TabInfo(
    val id: String = UUID.randomUUID().toString(),
    val config: ConnectionConfig?,
    var title: String
)

class TerminalPagerAdapter(
    activity: FragmentActivity
) : FragmentStateAdapter(activity) {

    companion object {
        const val WELCOME_ID = "welcome"
        const val CHEAT_ID   = "cheatsheet"
    }

    private val tabs = mutableListOf<TabInfo>()
    private val fragments = mutableMapOf<String, TerminalFragment>()

    // welcome + user tabs + cheatsheet
    override fun getItemCount(): Int = tabs.size + 2

    override fun createFragment(position: Int): Fragment = when {
        position == 0             -> TerminalFragment.newWelcome(WELCOME_ID).also { fragments[WELCOME_ID] = it }
        position == tabs.size + 1 -> TerminalFragment.newCheatSheet(CHEAT_ID).also { fragments[CHEAT_ID] = it }
        else -> {
            val tab = tabs[position - 1]
            TerminalFragment.newInstance(tab.config!!, tab.id).also { fragments[tab.id] = it }
        }
    }

    override fun getItemId(position: Int): Long = when {
        position == 0             -> WELCOME_ID.hashCode().toLong()
        position == tabs.size + 1 -> CHEAT_ID.hashCode().toLong()
        else                      -> tabs[position - 1].id.hashCode().toLong()
    }

    override fun containsItem(itemId: Long): Boolean =
        itemId == WELCOME_ID.hashCode().toLong() ||
        itemId == CHEAT_ID.hashCode().toLong() ||
        tabs.any { it.id.hashCode().toLong() == itemId }

    fun isFixedPage(viewPos: Int): Boolean = viewPos == 0 || viewPos >= tabs.size + 1

    // ── User tab methods (internal 0-based index) ─────────────────────────────

    fun addTab(tabInfo: TabInfo) {
        tabs.add(tabInfo)
        notifyDataSetChanged()
    }

    fun removeTab(internalIdx: Int) {
        if (internalIdx < 0 || internalIdx >= tabs.size) return
        val id = tabs[internalIdx].id
        fragments[id]?.disconnectAndClose()
        fragments.remove(id)
        tabs.removeAt(internalIdx)
        notifyDataSetChanged()
    }

    fun renameTab(internalIdx: Int, newTitle: String) {
        if (internalIdx < 0 || internalIdx >= tabs.size) return
        tabs[internalIdx].title = newTitle
        notifyDataSetChanged()
    }

    /** User tab by internal index (0-based). */
    fun getTab(internalIdx: Int): TabInfo? = tabs.getOrNull(internalIdx)

    /** User tab by ViewPager position. */
    fun getUserTabAtViewPos(viewPos: Int): TabInfo? = tabs.getOrNull(viewPos - 1)

    fun getTabCount(): Int = tabs.size

    fun getAllTabs(): List<TabInfo> = tabs.toList()

    fun getFragment(viewPos: Int): TerminalFragment? {
        val id = when {
            viewPos == 0             -> WELCOME_ID
            viewPos == tabs.size + 1 -> CHEAT_ID
            else                     -> tabs.getOrNull(viewPos - 1)?.id
        } ?: return null
        return fragments[id]
    }
}
