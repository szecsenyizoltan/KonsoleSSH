package hu.billman.konsolessh.ui.keybar

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.HorizontalScrollView
import androidx.core.view.isVisible

/**
 * Lüktető nyilak egy vízszintesen görgethető sáv két szélén, amíg arra még
 * van tartalom; koppintásra fél szélességnyit görgetnek.
 */
class ScrollHint(
    private val scrollView: HorizontalScrollView,
    private val right: View,
    private val left: View,
) {
    private val rightAnim = pulse(right, -SHIFT_PX)
    private val leftAnim = pulse(left, SHIFT_PX)
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { update() }

    init {
        right.setOnClickListener { scrollView.smoothScrollBy(scrollView.width / 2, 0) }
        left.setOnClickListener { scrollView.smoothScrollBy(-(scrollView.width / 2), 0) }
        scrollView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    }

    fun dispose() {
        rightAnim.cancel()
        leftAnim.cancel()
        scrollView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    private fun update() {
        show(right, rightAnim, scrollView.canScrollHorizontally(1))
        show(left, leftAnim, scrollView.canScrollHorizontally(-1))
    }

    private fun show(hint: View, anim: ObjectAnimator, visible: Boolean) {
        if (visible && !hint.isVisible) {
            hint.visibility = View.VISIBLE
            anim.start()
        } else if (!visible && hint.isVisible) {
            hint.visibility = View.GONE
            anim.cancel()
            hint.translationX = 0f
        }
    }

    private fun pulse(view: View, shift: Float) =
        ObjectAnimator.ofFloat(view, "translationX", 0f, shift).apply {
            duration = PULSE_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }

    private companion object {
        const val SHIFT_PX = 8f
        const val PULSE_MS = 900L
    }
}
