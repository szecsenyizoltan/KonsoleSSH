package hu.billman.konsolessh.ssh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Since Android 17 (targetSdk 37), TCP connections to LAN addresses need a
 * runtime permission. Without it the system drops the traffic, which the
 * client usually sees as a plain timeout, so the error message has to name
 * the permission or it would look like a server problem.
 */
object LocalNetworkAccess {

    fun isDenied(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
}
