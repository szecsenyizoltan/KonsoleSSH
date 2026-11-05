package hu.billman.konsolessh.ssh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import hu.billman.konsolessh.R

/**
 * Android 17 (targetSdk 37) óta a LAN-címekre induló TCP-kapcsolatokhoz
 * futásidejű engedély kell. Megtagadva a rendszer eldobja a forgalmat, amit a
 * kliens jellemzően sima időtúllépésként lát — ezért a hibaüzenetnek külön
 * kell utalnia az engedélyre, különben szerverhibának tűnne.
 */
object LocalNetworkAccess {

    private val BLOCK_MARKERS = listOf("timeout", "timed out", "operation not permitted")

    fun isDenied(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED

    /** A hibaüzenet illik-e a tiltott helyi hálózat tüneteire. */
    fun looksBlocked(message: String): Boolean {
        val lower = message.lowercase()
        return BLOCK_MARKERS.any { it in lower }
    }

    /** A tiltásra utaló üzenetet adja vissza, ha az engedély hiánya lehet az ok. */
    fun explain(context: Context, message: String): String =
        if (isDenied(context) && looksBlocked(message)) {
            context.getString(R.string.err_local_network_blocked)
        } else {
            message
        }
}
