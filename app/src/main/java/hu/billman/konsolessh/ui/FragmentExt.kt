package hu.billman.konsolessh.ui

import android.content.Context
import androidx.fragment.app.Fragment

/**
 * Aszinkron callback-ekben a `requireContext()` race-elhet: a Fragment
 * detached (rotáció, gyors tab-close) állapotban az IllegalStateException-t
 * dob. Ez az extension null-safe módon adja át a Context-et a block-nak,
 * ha a Fragment még attached; ha nem, NOOP.
 *
 * Használat:
 * ```
 * fragment.runIfAttached { ctx ->
 *     AlertDialog.Builder(ctx).show()
 * }
 * ```
 */
inline fun Fragment.runIfAttached(block: (Context) -> Unit) {
    if (!isAdded) return
    val ctx = context ?: return
    block(ctx)
}
