package hu.szecsenyi.konsolessh.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import hu.szecsenyi.konsolessh.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps the process alive while SSH connections are active.
 * Start with ACTION_START (connectionCount++), stop with ACTION_STOP (connectionCount--).
 * When the counter reaches 0 the service stops itself.
 */
class SshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ssh_keepalive"
        private const val NOTIF_ID   = 1
        const val ACTION_START = "hu.szecsenyi.konsolessh.SSH_START"
        const val ACTION_STOP  = "hu.szecsenyi.konsolessh.SSH_STOP"

        private val activeCount = AtomicInteger(0)

        fun start(context: Context) {
            val intent = Intent(context, SshForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SshForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // API 34+: startForeground must include the service type
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else
                0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = when (intent?.action) {
            ACTION_START -> activeCount.incrementAndGet()
            ACTION_STOP  -> activeCount.decrementAndGet()
            else         -> activeCount.get()
        }
        if (count <= 0) {
            activeCount.set(0)
            stopSelf()
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(count))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeCount.set(0)
        super.onDestroy()
    }

    private fun buildNotification(count: Int): Notification {
        val text = if (count == 1) "1 aktív SSH kapcsolat" else "$count aktív SSH kapcsolat"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KonsoleSSH")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "SSH kapcsolat",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Aktív SSH kapcsolatok életben tartása" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }
}
