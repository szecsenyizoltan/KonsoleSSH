package hu.billman.konsolessh.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.R
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.OutputChunk
import hu.billman.konsolessh.terminal.CheatSheets
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.Monospace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

/**
 * One SSH tab. The session lives in the SessionManager; the page gets only
 * the tab's id (no secrets), draws its output and sends input through the
 * [MainViewModel]. [onTerminal] receives the view while it is shown, for
 * the key bar, zoom and the hardware keyboard.
 */
@Composable
fun SshTerminalPage(tabId: String, viewModel: MainViewModel, onTerminal: (TerminalView?) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab = state.sessions.tab(tabId)
    val status = tab?.status ?: ConnectionStatus.NONE
    val name = tab?.displayName.orEmpty()
    val statusText = when (status) {
        ConnectionStatus.NONE -> stringResource(R.string.status_no_connection)
        ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting, name)
        ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected_suffix, name)
        ConnectionStatus.DISCONNECTED -> stringResource(R.string.status_disconnected_suffix, name)
    }
    var view by remember { mutableStateOf<TerminalView?>(null) }

    TerminalPage(
        status = statusText,
        create = { context ->
            TerminalView(context).apply {
                onKeyInput = { bytes -> viewModel.send(tabId, bytes) }
                // Query answers are not keystrokes: sticky modifiers must not touch them.
                onTerminalResponse = { bytes -> viewModel.sendRaw(tabId, bytes) }
                onTerminalResize = { cols, rows -> viewModel.resize(tabId, cols, rows) }
                // The first connect needs the real terminal size, known after layout.
                doOnLayout { viewModel.connectIfNew(tabId, currentCols, currentRows) }
            }
        },
        onView = { view = it },
        onTerminal = onTerminal,
        onReconnect = if (status == ConnectionStatus.DISCONNECTED) {
            { view?.let { viewModel.reconnect(tabId, it.currentCols, it.currentRows) } }
        } else {
            null
        },
    )

    val terminal = view ?: return
    // The saved size is applied once, when the tab's view is created.
    LaunchedEffect(terminal) {
        val sp = viewModel.state.first { it.fontSizeSp > 0f }.fontSizeSp
        terminal.post { terminal.setFontSize(sp) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(terminal, tabId) {
        val feed = TerminalFeed(terminal, tabId, viewModel)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { feed.collect() }
    }
}

/**
 * Feeds a tab's output into one view. It subscribes first, then draws the
 * stored snapshot; chunk positions make the seam exact, so nothing is drawn
 * twice or skipped. If the live flow dropped chunks while rendering lagged,
 * the gap is filled from the snapshot. Replayed output was answered when it
 * first arrived, so it is not answered again.
 */
private class TerminalFeed(
    private val view: TerminalView,
    private val tabId: String,
    private val viewModel: MainViewModel,
) {

    /** End of the output already drawn in the view (absolute stream position). */
    private var renderedEnd = 0L

    suspend fun collect() {
        val flow = viewModel.output(tabId) ?: return
        flow.onSubscription { renderSnapshot() }.collect { chunk ->
            if (chunk.start > renderedEnd) renderSnapshot()
            render(chunk, respond = true)
        }
    }

    private suspend fun renderSnapshot() {
        viewModel.snapshot(tabId)?.let { render(it, respond = false) }
    }

    // Sequential calls from one collector, so renderedEnd needs no lock. ANSI
    // parsing runs off the main thread, so large output does not stall the UI.
    private suspend fun render(chunk: OutputChunk, respond: Boolean) {
        if (chunk.end <= renderedEnd) return
        val from = maxOf(chunk.start, renderedEnd)
        val offset = (from - chunk.start).toInt()
        val bytes = if (offset == 0) chunk.bytes else chunk.bytes.copyOfRange(offset, chunk.bytes.size)
        withContext(Dispatchers.Default) { view.append(bytes, bytes.size, respond) }
        renderedEnd = chunk.end
    }
}

/** A page without SSH: the welcome screen or a cheat sheet. */
@Composable
fun StaticTerminalPage(page: PageKey, onTerminal: (TerminalView?) -> Unit) {
    val hungarian = LocalConfiguration.current.locales[0].language == "hu"
    TerminalPage(
        status = stringResource(R.string.status_no_connection),
        create = { context ->
            TerminalView(context).apply {
                if (page == PageKey.Welcome) {
                    showWelcome(context)
                } else {
                    val sheet = {
                        if (page == PageKey.Linux) CheatSheets.linux(hungarian) else CheatSheets.tmux(hungarian)
                    }
                    showSheet(sheet())
                    // The sheets are laid out for the current width, so they are redrawn on
                    // resize; the old copy must leave the scrollback too, or it shows twice.
                    onTerminalResize = { _, _ ->
                        resetContent()
                        showSheet(sheet())
                    }
                }
            }
        },
        onView = {},
        onTerminal = onTerminal,
        onReconnect = null,
    )
}

private fun TerminalView.showSheet(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    append(bytes, bytes.size)
    post { scrollToTop() }
}

private fun TerminalView.showWelcome(context: Context) {
    horizontalScrollEnabled = false
    post { setFontSize(WELCOME_FONT_SP) }
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
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull() ?: "?"
    val banner = buildString {
        append("\r\n\r\n  $title  $hint v$version$reset\r\n\r\n")
        lines.forEach { res -> if (res == 0) append("\r\n") else append("$dim  ${context.getString(res)}$reset\r\n") }
        append("\r\n$hint  ${context.getString(R.string.welcome_swipe_hint)}$reset\r\n")
    }
    val bytes = banner.toByteArray(Charsets.UTF_8)
    append(bytes, bytes.size)
}

/** A status line over a terminal view, with a reconnect button over the view when [onReconnect] is set. */
@Composable
private fun TerminalPage(
    status: String,
    create: (Context) -> TerminalView,
    onView: (TerminalView) -> Unit,
    onTerminal: (TerminalView?) -> Unit,
    onReconnect: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize().background(KonsoleColors.Background)) {
        Text(
            text = status,
            color = KonsoleColors.StatusText,
            fontFamily = Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(KonsoleColors.StatusBar)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            var created by remember { mutableStateOf<TerminalView?>(null) }
            AndroidView(
                factory = { context -> create(context).also { created = it } },
                // The view paints its whole canvas; unclipped, that would cover the bars above it.
                modifier = Modifier.fillMaxSize().clipToBounds(),
            )
            created?.let { view ->
                DisposableEffect(view) {
                    onView(view)
                    onTerminal(view)
                    onDispose { onTerminal(null) }
                }
            }
            if (onReconnect != null) ReconnectButton(onReconnect, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ReconnectButton(onClick: () -> Unit, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.clickable(onClick = onClick)) {
        Surface(
            shape = CircleShape,
            color = Color(0xCC1C2128),
            border = BorderStroke(1.dp, Color(0xFFFF6600)),
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sym_refresh), color = KonsoleColors.Accent, fontSize = 32.sp)
            }
        }
        Text(
            text = stringResource(R.string.action_reconnect),
            color = KonsoleColors.Accent,
            fontFamily = Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private const val WELCOME_FONT_SP = 16f
