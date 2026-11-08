package hu.billman.konsolessh.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.FragmentTerminalBinding
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.OutputChunk
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.terminal.TerminalView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Egy SSH-fül megjelenítése. A munkamenet a [hu.billman.konsolessh.session.SessionManager]-ben
 * él; a Fragment csak a fül azonosítóját kapja (titkot nem), és a
 * [MainViewModel]-en át figyel és ad parancsot.
 */
class SshTerminalFragment : Fragment(), TerminalPage {

    private val viewModel: MainViewModel by activityViewModels { KonsoleViewModelFactory.from(requireContext()) }

    private var _binding: FragmentTerminalBinding? = null
    override val terminalView: TerminalView? get() = _binding?.terminalView

    val tabId: String get() = requireArguments().getString(ARG_TAB_ID).orEmpty()

    /** A nézetbe már kirajzolt kimenet vége (abszolút folyampozíció). */
    private var renderedEnd = 0L

    /** Az ANSI-feldolgozás itt fut, hogy nagy kimenet ne akassza a felületet. */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FragmentTerminalBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val terminal = _binding?.terminalView ?: return
        renderedEnd = 0L

        viewModel.fontSizeSp.takeIf { it > 0f }?.let { sp -> terminal.post { terminalView?.setFontSize(sp) } }
        terminal.onKeyInput = { bytes -> viewModel.send(tabId, bytes) }
        terminal.onTerminalResize = { cols, rows -> viewModel.resize(tabId, cols, rows) }
        _binding?.btnReconnect?.setOnClickListener {
            viewModel.reconnect(tabId, terminal.currentCols, terminal.currentRows)
        }
        // The first connect needs the real terminal size, known after layout.
        terminal.doOnLayout { viewModel.connectIfNew(tabId, terminal.currentCols, terminal.currentRows) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.sessionsState
                        .map { it.tab(tabId) }
                        .filterNotNull()
                        .distinctUntilChanged { a, b -> a.status == b.status && a.displayName == b.displayName }
                        .collect(::renderStatus)
                }
                launch { collectOutput() }
            }
        }
    }

    // Subscribe first, then draw the stored snapshot; chunk positions make the
    // seam exact, so nothing is drawn twice or skipped. If the live flow dropped
    // chunks while rendering lagged, the gap is filled from the snapshot.
    private suspend fun collectOutput() {
        val view = terminalView ?: return
        val flow = viewModel.output(tabId) ?: return
        flow.onSubscription { renderSnapshot(view) }.collect { chunk ->
            if (chunk.start > renderedEnd) renderSnapshot(view)
            render(view, chunk)
        }
    }

    private suspend fun renderSnapshot(view: TerminalView) {
        viewModel.snapshot(tabId)?.let { render(view, it) }
    }

    // Sequential calls from one collector, so renderedEnd needs no lock.
    private suspend fun render(view: TerminalView, chunk: OutputChunk) {
        if (chunk.end <= renderedEnd) return
        val from = maxOf(chunk.start, renderedEnd)
        val offset = (from - chunk.start).toInt()
        val bytes = if (offset == 0) chunk.bytes else chunk.bytes.copyOfRange(offset, chunk.bytes.size)
        withContext(renderDispatcher) { view.append(bytes, bytes.size) }
        renderedEnd = chunk.end
    }

    private fun renderStatus(tab: TabState) {
        val binding = _binding ?: return
        binding.btnReconnect.visibility = if (tab.status == ConnectionStatus.DISCONNECTED) View.VISIBLE else View.GONE
        binding.statusBar.text = when (tab.status) {
            ConnectionStatus.NONE -> getString(R.string.status_no_connection)
            ConnectionStatus.CONNECTING -> getString(R.string.status_connecting, tab.displayName)
            ConnectionStatus.CONNECTED -> getString(R.string.status_connected_suffix, tab.displayName)
            ConnectionStatus.DISCONNECTED -> getString(R.string.status_disconnected_suffix, tab.displayName)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TAB_ID = "tab_id"

        fun newInstance(tabId: String) = SshTerminalFragment().apply { arguments = bundleOf(ARG_TAB_ID to tabId) }
    }
}
