package hu.billman.konsolessh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.FragmentTerminalBinding
import hu.billman.konsolessh.terminal.CheatSheets
import hu.billman.konsolessh.terminal.TerminalView

/** A billentyűsor ezen át éri el az aktuális oldal terminálnézetét. */
interface TerminalPage {
    val terminalView: TerminalView?
}

/** Rögzített oldalak SSH nélkül: üdvözlő képernyő, Linux- és tmux-puska. */
class StaticTerminalFragment : Fragment(), TerminalPage {

    enum class Mode { WELCOME, LINUX, TMUX }

    private var _binding: FragmentTerminalBinding? = null
    override val terminalView: TerminalView? get() = _binding?.terminalView

    private val mode: Mode
        get() = Mode.valueOf(requireArguments().getString(ARG_MODE) ?: Mode.WELCOME.name)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentTerminalBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = _binding ?: return
        binding.statusBar.text = getString(R.string.status_no_connection)
        binding.btnReconnect.visibility = View.GONE
        when (mode) {
            Mode.WELCOME -> showWelcome(binding)
            // The sheets are laid out for the current width, so redraw on resize;
            // the old copy must leave the scrollback too, or it shows twice.
            Mode.LINUX, Mode.TMUX -> {
                showSheet()
                binding.terminalView.onTerminalResize = { _, _ ->
                    binding.terminalView.resetContent()
                    showSheet()
                }
            }
        }
    }

    private fun showSheet() {
        val view = terminalView ?: return
        val hungarian = resources.configuration.locales[0].language == "hu"
        val text = if (mode == Mode.LINUX) CheatSheets.linux(hungarian) else CheatSheets.tmux(hungarian)
        val bytes = text.toByteArray(Charsets.UTF_8)
        view.append(bytes, bytes.size)
        view.post { terminalView?.scrollToTop() }
    }

    private fun showWelcome(binding: FragmentTerminalBinding) {
        binding.terminalView.horizontalScrollEnabled = false
        binding.terminalView.post { terminalView?.setFontSize(WELCOME_FONT_SP) }
        val reset = "\u001B[0m"
        val dim = "\u001B[38;5;244m"
        val hint = "\u001B[38;5;240m"
        val shades = listOf("\u001B[38;5;214m", "\u001B[38;5;208m", "\u001B[38;5;202m")
        val title = buildString {
            // Seven letters in the lightest shade, then two, then one.
            "KonsoleSSH".forEachIndexed { i, ch ->
                append(shades[if (i < 7) 0 else if (i < 9) 1 else 2]).append(ch)
            }
            append(reset)
        }
        val lines = listOf(
            R.string.welcome_line_1, R.string.welcome_line_2, 0,
            R.string.welcome_line_3, R.string.welcome_line_4, R.string.welcome_line_5, 0,
            R.string.welcome_line_6, R.string.welcome_line_7, R.string.welcome_line_8, 0,
            R.string.welcome_line_9,
        )
        val banner = buildString {
            append("\r\n\r\n  $title  $hint v${versionName()}$reset\r\n\r\n")
            lines.forEach { res -> if (res == 0) append("\r\n") else append("$dim  ${getString(res)}$reset\r\n") }
            append("\r\n$hint  ${getString(R.string.welcome_swipe_hint)}$reset\r\n")
        }
        val bytes = banner.toByteArray(Charsets.UTF_8)
        binding.terminalView.append(bytes, bytes.size)
    }

    private fun versionName(): String = runCatching {
        val ctx = requireContext()
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_MODE = "mode"
        private const val WELCOME_FONT_SP = 16f

        fun newInstance(mode: Mode) = StaticTerminalFragment().apply { arguments = bundleOf(ARG_MODE to mode.name) }
    }
}
