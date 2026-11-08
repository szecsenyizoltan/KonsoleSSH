package hu.billman.konsolessh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import hu.billman.konsolessh.AppContainer
import hu.billman.konsolessh.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Életben tartja a folyamatot, amíg az SSH-munkamenetek futnak, és az aktív
 * kapcsolatok számát mutatja az értesítésben. A munkameneteket nem ő
 * birtokolja: azok a [hu.billman.konsolessh.session.SessionManager]-ben élnek.
 */
class SshForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        AppContainer.from(this).sessionManager.state
            .map { it.connectedCount }
            .distinctUntilChanged()
            .onEach { count -> notificationManager()?.notify(NOTIF_ID, buildNotification(count)) }
            .launchIn(scope)
    }

    // Sessions live in this process only; after a process restart there is
    // nothing to resume, so the system should not recreate the service.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun buildNotification(count: Int): Notification {
        val text = when (count) {
            0 -> getString(R.string.service_idle)
            1 -> getString(R.string.service_one_active)
            else -> getString(R.string.service_n_active, count)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, if (count > 0) CHANNEL_ID_ACTIVE else CHANNEL_ID_IDLE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setNumber(count)
            .setBadgeIconType(
                if (count > 0) NotificationCompat.BADGE_ICON_SMALL else NotificationCompat.BADGE_ICON_NONE,
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannels() {
        val nm = notificationManager() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_IDLE,
                getString(R.string.channel_idle_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_idle_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_ACTIVE,
                getString(R.string.channel_active_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_active_desc)
                setShowBadge(true)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID_IDLE = "ssh_idle"
        private const val CHANNEL_ID_ACTIVE = "ssh_active"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SshForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshForegroundService::class.java))
        }
    }
}
