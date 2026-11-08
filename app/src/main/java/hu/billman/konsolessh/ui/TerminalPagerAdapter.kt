package hu.billman.konsolessh.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Oldalak: üdvözlő, az SSH-fülek, Linux-puska, tmux-puska. Csak a fülek
 * azonosítóit tartja; Fragment-referenciát nem, mert a FragmentStateAdapter
 * forgatás és folyamathalál után maga állítja vissza a Fragmenteket.
 */
class TerminalPagerAdapter(private val activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var tabIds: List<String> = emptyList()

    fun submit(ids: List<String>) {
        if (ids == tabIds) return
        tabIds = ids
        @Suppress("NotifyDataSetChanged") // stable ids: ViewPager2 keeps the surviving pages
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tabIds.size + FIXED_PAGES

    override fun createFragment(position: Int): Fragment = when {
        position == 0 -> StaticTerminalFragment.newInstance(StaticTerminalFragment.Mode.WELCOME)
        position == tabIds.size + 1 -> StaticTerminalFragment.newInstance(StaticTerminalFragment.Mode.LINUX)
        position == tabIds.size + 2 -> StaticTerminalFragment.newInstance(StaticTerminalFragment.Mode.TMUX)
        else -> SshTerminalFragment.newInstance(tabIds[position - 1])
    }

    override fun getItemId(position: Int): Long = keyAt(position).hashCode().toLong()

    override fun containsItem(itemId: Long): Boolean =
        (FIXED_KEYS + tabIds).any { it.hashCode().toLong() == itemId }

    fun isFixedPage(position: Int): Boolean = position == 0 || position > tabIds.size

    /** Az SSH-fül azonosítója az adott oldalon, rögzített oldalnál null. */
    fun tabIdAt(position: Int): String? = tabIds.getOrNull(position - 1)

    fun positionOf(tabId: String): Int = tabIds.indexOf(tabId).let { if (it < 0) -1 else it + 1 }

    /** Az oldal Fragmentje; a ViewPager2 "f<itemId>" taggel teszi a FragmentManagerbe. */
    fun pageAt(position: Int): TerminalPage? =
        activity.supportFragmentManager.findFragmentByTag("f${getItemId(position)}") as? TerminalPage

    private fun keyAt(position: Int): String = when {
        position == 0 -> WELCOME_KEY
        position == tabIds.size + 1 -> LINUX_KEY
        position == tabIds.size + 2 -> TMUX_KEY
        else -> tabIds[position - 1]
    }

    private companion object {
        const val FIXED_PAGES = 3
        const val WELCOME_KEY = "welcome"
        const val LINUX_KEY = "cheatsheet"
        const val TMUX_KEY = "tmuxsheet"
        val FIXED_KEYS = listOf(WELCOME_KEY, LINUX_KEY, TMUX_KEY)
    }
}
