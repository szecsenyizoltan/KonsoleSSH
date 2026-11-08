package hu.billman.konsolessh.ssh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 17 (targetSdk 37) óta a LAN-címekre induló TCP-kapcsolatokhoz
 * futásidejű engedély kell. Megtagadva a rendszer eldobja a forgalmat, amit a
 * kliens jellemzően sima időtúllépésként lát — ezért a hibaüzenetnek külön
 * kell utalnia az engedélyre, különben szerverhibának tűnne.
 */
object LocalNetworkAccess {

    fun isDenied(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
}
