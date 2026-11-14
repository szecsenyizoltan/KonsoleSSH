package hu.billman.konsolessh.ui.keybar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.billman.konsolessh.R
import hu.billman.konsolessh.terminal.KeySequences
import hu.billman.konsolessh.terminal.ModifierState
import hu.billman.konsolessh.terminal.TerminalClipboard
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.Monospace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import hu.billman.konsolessh.terminal.Modifier as Key

/**
 * The key rows under the terminal: sticky modifiers, Esc and Tab, arrows,
 * F1-F12, Ctrl combinations, the keyboard and the upload button. The
 * modifier state comes from the ViewModel; which extra rows are open is
 * remembered across recreation. Keys never take focus from the terminal.
 */
@Composable
fun Keybar(modifiers: ModifierState, host: KeybarHost) {
    var fnOpen by rememberSaveable { mutableStateOf(false) }
    var arrowsOpen by rememberSaveable { mutableStateOf(false) }
    val focus = { host.currentTerminal()?.focusInput() }
    fun send(bytes: ByteArray) {
        if (bytes.isNotEmpty()) host.sendKey(bytes)
        focus()
    }

    Column(modifier = Modifier.fillMaxWidth().background(KonsoleColors.KeyBar)) {
        if (fnOpen) {
            KeyRow(height = ROW_HEIGHT) {
                KeySequences.FUNCTION_KEYS.forEachIndexed { i, code ->
                    KeyButton("F${i + 1}") { send(KeySequences.escape(code)) }
                }
            }
        }
        if (Key.CTRL in modifiers) {
            val copied = stringResource(R.string.copied)
            val pasted = stringResource(R.string.pasted)
            KeyRow(height = ROW_HEIGHT) {
                CTRL_KEYS.forEach { c ->
                    KeyButton(stringResource(R.string.ctrl_key_label, c)) {
                        when (c) {
                            'C' -> copyOrInterrupt(host, copied)
                            'V' -> paste(host, pasted)
                            else -> {
                                host.sendRaw(byteArrayOf((c.code - 'A'.code + 1).toByte()))
                                focus()
                            }
                        }
                    }
                }
            }
        }
        if (arrowsOpen) {
            KeyRow(height = ROW_HEIGHT) {
                listOf('D' to "←", 'A' to "↑", 'B' to "↓", 'C' to "→").forEach { (dir, label) ->
                    KeyButton(label) { send(host.currentTerminal()?.cursorKeyBytes(dir) ?: byteArrayOf()) }
                }
            }
        }
        KeyRow(height = MAIN_ROW_HEIGHT) {
            KeyButton(stringResource(R.string.sym_keyboard), onClick = host::toggleKeyboard)
            KeyButton(stringResource(R.string.key_fn).uppercase(), active = fnOpen) {
                fnOpen = !fnOpen
                focus()
            }
            Divider()
            MODIFIER_KEYS.forEach { (key, label) ->
                KeyButton(stringResource(label), active = key in modifiers) {
                    host.toggleModifier(key)
                    focus()
                }
            }
            Divider()
            KeyButton(stringResource(R.string.key_esc)) { send(byteArrayOf(KeySequences.ESC)) }
            KeyButton(stringResource(R.string.key_tab)) { send(byteArrayOf(KeySequences.TAB)) }
            Divider()
            KeyButton(stringResource(R.string.sym_arrow_up), active = arrowsOpen) {
                arrowsOpen = !arrowsOpen
                focus()
            }
            Divider()
            KeyButton(
                label = stringResource(R.string.sym_folder),
                description = stringResource(R.string.content_desc_upload),
                onClick = host::pickUploadFile,
            )
        }
    }
}

// Ctrl+C copies a selection if there is one, otherwise it is the interrupt.
private fun copyOrInterrupt(host: KeybarHost, copied: String) {
    val selection = host.currentTerminal()?.getSelectedText().orEmpty()
    if (selection.isNotEmpty()) {
        TerminalClipboard.text = selection
        host.showMessage(copied)
    } else {
        host.sendRaw(byteArrayOf(CTRL_C))
        host.currentTerminal()?.focusInput()
    }
}

private fun paste(host: KeybarHost, pasted: String) {
    val clip = TerminalClipboard.text
    if (!clip.isNullOrEmpty()) {
        host.currentTerminal()?.pasteText(clip)
        host.showMessage(pasted)
    }
}

/**
 * A horizontally scrolling row of keys. While there is more in a direction,
 * a pulsing arrow sits beside the row on that side, never over a key;
 * tapping it scrolls half a row.
 */
@Composable
private fun KeyRow(height: Dp, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(height)) {
        val half = with(LocalDensity.current) { maxWidth.toPx() / 2 }.toInt()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (scroll.canScrollBackward) {
                ScrollHint("◀", Alignment.CenterStart, PULSE_SHIFT) { scope.launch { scroll.animateScrollBy(-half) } }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).horizontalScroll(scroll),
            ) { content() }
            if (scroll.canScrollForward) {
                ScrollHint("▶", Alignment.CenterEnd, -PULSE_SHIFT) { scope.launch { scroll.animateScrollBy(half) } }
            }
        }
    }
}

private suspend fun ScrollState.animateScrollBy(delta: Int) = animateScrollTo((value + delta).coerceIn(0, maxValue))

@Composable
private fun ScrollHint(arrow: String, alignment: Alignment, shift: Dp, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "scroll-hint").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "scroll-hint-offset",
    )
    Box(
        contentAlignment = alignment,
        modifier = Modifier.width(32.dp).background(KonsoleColors.KeyBar).clickable(onClick = onClick),
    ) {
        Text(
            text = arrow,
            color = KonsoleColors.Accent,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().offset { IntOffset((shift * pulse).roundToPx(), 0) },
        )
    }
}

/** A key: monospace label, orange background while active, orange text for a moment after a press. */
@Composable
private fun KeyButton(label: String, active: Boolean = false, description: String? = null, onClick: () -> Unit) {
    var flashes by remember { mutableIntStateOf(0) }
    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flashes) {
        if (flashes == 0) return@LaunchedEffect
        flashing = true
        delay(FLASH_MS)
        flashing = false
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(36.dp)
            .widthIn(min = 48.dp)
            .background(if (active) KonsoleColors.Accent else Color.Transparent)
            .clickable {
                flashes++
                onClick()
            }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            color = if (flashing && !active) KonsoleColors.Accent else Color.White,
            fontFamily = Monospace,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(28.dp)
            .background(KonsoleColors.TextSecondary),
    )
}

private val CTRL_KEYS = listOf('A', 'B', 'C', 'D', 'V', 'Z')
private val MODIFIER_KEYS = listOf(
    Key.CTRL to R.string.key_ctrl,
    Key.SHIFT to R.string.key_shift,
    Key.ALT to R.string.key_alt,
    Key.ALTGR to R.string.key_altgr,
)
private const val CTRL_C: Byte = 3
private const val FLASH_MS = 300L
private const val PULSE_MS = 900
private val PULSE_SHIFT = 3.dp
private val ROW_HEIGHT = 36.dp
private val MAIN_ROW_HEIGHT = 40.dp
