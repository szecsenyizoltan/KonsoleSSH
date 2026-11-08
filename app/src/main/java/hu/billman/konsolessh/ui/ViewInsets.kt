package hu.billman.konsolessh.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge (targetSdk 35+): a rendszersávok és a kivágás helyét
 * paddingként adja vissza, alul a billentyűzetet is figyelembe véve, hogy a
 * tartalmat ne takarja.
 */
fun View.applySystemBarAndImePadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        insets
    }
}
