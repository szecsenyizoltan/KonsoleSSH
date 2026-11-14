package hu.billman.konsolessh.terminal

import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue

/**
 * Converts the terminal's font size between sp and pixels the way the
 * system does. Since Android 14 large font scales are applied
 * non-linearly; the old `sp * scaledDensity` ignored that and drew the
 * terminal larger than other text at the same size.
 */
internal object FontScaling {

    fun spToPx(sp: Float, metrics: DisplayMetrics): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)

    fun pxToSp(px: Float, metrics: DisplayMetrics): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, px, metrics)
        } else {
            @Suppress("DEPRECATION")
            px / metrics.scaledDensity
        }
}
