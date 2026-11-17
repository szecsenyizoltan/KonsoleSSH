package hu.billman.konsolessh.ui.main

/** The pages of the pager: the welcome page, one per SSH tab, then two cheat sheets. */
sealed interface PageKey {
    /** The page's identity for the pager, which must survive in a Bundle. */
    val key: String

    data object Welcome : PageKey {
        override val key = "welcome"
    }

    data class Tab(val id: String) : PageKey {
        override val key = "tab:$id"
    }

    data object Linux : PageKey {
        override val key = "linux"
    }

    data object Tmux : PageKey {
        override val key = "tmux"
    }

    companion object {
        fun pages(tabIds: List<String>): List<PageKey> = listOf(Welcome) + tabIds.map(::Tab) + listOf(Linux, Tmux)
    }
}
