package hu.szecsenyi.konsolessh.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import hu.szecsenyi.konsolessh.R

object KonsoleToast {

    private const val AUTO_DISMISS_MS = 4000L
    private const val ANIM_DURATION_MS = 250L

    fun show(anchor: View, message: String) {
        val root = anchor.rootView as? ViewGroup ?: return
        val ctx = anchor.context

        val toast = LayoutInflater.from(ctx).inflate(R.layout.layout_konsole_toast, root, false)
        toast.findViewById<TextView>(R.id.toastMessage).text = message
        toast.findViewById<View>(R.id.btnToastClose).setOnClickListener { dismiss(root, toast) }

        val density = ctx.resources.displayMetrics.density
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = (100 * density).toInt() }

        root.addView(toast, lp)
        toast.postDelayed({ dismiss(root, toast) }, AUTO_DISMISS_MS)
    }

    private fun dismiss(root: ViewGroup, view: View) {
        if (view.parent == null) return
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.6f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.6f),
                ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
            )
            duration = ANIM_DURATION_MS
            doOnEnd { root.removeView(view) }
            start()
        }
    }
}
