package hu.billman.konsolessh.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Lets the user swipe the content sideways, left or right, to dismiss it.
 * The content follows the finger and fades a little; past a third of its
 * width, or on a quick fling, it slides out and [onDismiss] runs, otherwise
 * it springs back. Vertical scrolling and taps inside stay as they are.
 */
fun Modifier.swipeToDismissHorizontally(onDismiss: () -> Unit): Modifier = composed {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    this
        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
        .graphicsLayer {
            alpha = 1f - FADE * (abs(offsetX.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
        .pointerInput(onDismiss) {
            val width = size.width.toFloat()
            val velocity = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { velocity.resetTracking() },
                onDragEnd = {
                    val speed = velocity.calculateVelocity().x
                    val far = abs(offsetX.value) > width * DISMISS_FRACTION
                    val fling = abs(speed) > FLING_PX_PER_S && sign(speed) == sign(offsetX.value)
                    scope.launch {
                        if (far || fling) {
                            offsetX.animateTo(sign(offsetX.value) * width)
                            onDismiss()
                        } else {
                            offsetX.animateTo(0f)
                        }
                    }
                },
                onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
            ) { change, amount ->
                change.consume()
                velocity.addPosition(change.uptimeMillis, change.position)
                scope.launch { offsetX.snapTo(offsetX.value + amount) }
            }
        }
}

private const val DISMISS_FRACTION = 0.33f
private const val FLING_PX_PER_S = 1500f
private const val FADE = 0.5f
