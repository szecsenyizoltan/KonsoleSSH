package hu.billman.konsolessh.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.billman.konsolessh.ui.theme.KonsoleColors
import hu.billman.konsolessh.ui.theme.Monospace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The app's own toasts: shown over the screen near the bottom, several at
 * once if they come together, each with an optional action. [ToastHost]
 * draws them.
 */
class ToastHostState {

    internal class Toast(
        val id: Long,
        val message: String,
        val actionLabel: String?,
        val timeoutMs: Long,
        val wholeToastClickable: Boolean,
        private val onAction: (() -> Unit)?,
        private val onTimeout: (() -> Unit)?,
    ) {
        private var resolved = false

        /** The action and the timeout are alternatives: only the first of them runs. */
        fun act() = resolveWith(onAction)
        fun timeOut() = resolveWith(onTimeout)

        /**
         * The close button. On a toast that resolves either way it counts as
         * the action; on any other it only closes, so closing an "Undo" toast
         * does not undo.
         */
        fun close() = if (wholeToastClickable) act() else resolveWith(null)

        private fun resolveWith(block: (() -> Unit)?) {
            if (resolved) return
            resolved = true
            block?.invoke()
        }
    }

    internal val toasts = mutableStateListOf<Toast>()
    private val ids = AtomicLong()

    fun show(message: String) = add(message, null, AUTO_DISMISS_MS, false, null, null)

    /** A toast with an action button (e.g. "Undo") that stays for [timeoutMs]. */
    fun showWithAction(message: String, actionLabel: String, timeoutMs: Long, onAction: () -> Unit) =
        add(message, actionLabel, timeoutMs, false, onAction, null)

    /**
     * A toast that resolves exactly once: [onDone] runs when the user taps it
     * (or its action) or when [timeoutMs] passes, whichever comes first.
     * Used where waiting means agreeing, like closing a failed tab.
     */
    fun showAutoAction(message: String, actionLabel: String, timeoutMs: Long, onDone: () -> Unit) =
        add(message, actionLabel, timeoutMs, true, onDone, onDone)

    private fun add(
        message: String,
        actionLabel: String?,
        timeoutMs: Long,
        wholeToastClickable: Boolean,
        onAction: (() -> Unit)?,
        onTimeout: (() -> Unit)?,
    ) {
        toasts += Toast(ids.incrementAndGet(), message, actionLabel, timeoutMs, wholeToastClickable, onAction, onTimeout)
    }

    internal fun remove(toast: Toast) {
        toasts.remove(toast)
    }

    private companion object {
        const val AUTO_DISMISS_MS = 4000L
    }
}

/** Draws the toasts of [state], stacked, newest at the bottom. */
@Composable
fun ToastHost(state: ToastHostState, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        state.toasts.forEach { toast -> key(toast.id) { ToastView(toast) { state.remove(toast) } } }
    }
}

@Composable
private fun ToastView(toast: ToastHostState.Toast, onGone: () -> Unit) {
    val exit = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    fun dismiss(then: () -> Unit) {
        scope.launch {
            then()
            exit.animateTo(EXIT_SCALE, tween(EXIT_MS))
            onGone()
        }
    }
    LaunchedEffect(toast.id) {
        delay(toast.timeoutMs)
        dismiss(toast::timeOut)
    }
    val fade = (exit.value - EXIT_SCALE) / (1f - EXIT_SCALE)
    Surface(
        color = Color(0xEE1C2128),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, KonsoleColors.Error),
        modifier = Modifier
            .graphicsLayer {
                scaleX = exit.value
                scaleY = exit.value
                alpha = fade
            }
            .then(if (toast.wholeToastClickable) Modifier.clickable { dismiss(toast::act) } else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            Text(
                text = toast.message,
                color = KonsoleColors.TextPrimary,
                fontFamily = Monospace,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp),
            )
            if (toast.actionLabel != null) {
                Text(
                    text = toast.actionLabel.uppercase(),
                    color = KonsoleColors.Accent,
                    fontFamily = Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { dismiss(toast::act) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                text = "✕",
                color = KonsoleColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable { dismiss(toast::close) }
                    .padding(8.dp),
            )
        }
    }
}

private const val EXIT_SCALE = 0.6f
private const val EXIT_MS = 250
