package hu.szecsenyi.konsolessh.terminal

/** In-app clipboard for copy/paste within SSH terminal tabs. */
object TerminalClipboard {
    var text: String? = null

    fun clear() { text = null }
}
