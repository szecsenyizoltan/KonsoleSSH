package hu.szecsenyi.konsolessh.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.ui.ConnectionStatus
import hu.szecsenyi.konsolessh.ui.TabInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

typealias PasswordPrompterFn = (displayHost: String, callback: (String?) -> Unit) -> Unit

class SshForegroundService : Service() {

    // ── Binder ────────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): SshForegroundService = this@SshForegroundService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean = true  // allow rebind
    override fun onRebind(intent: Intent?) = Unit

    // ── Session state ─────────────────────────────────────────────────────────

    class OutputBuffer(private val maxBytes: Int = 256 * 1024) {
        private val buf = ByteArrayOutputStream()

        @Synchronized fun append(bytes: ByteArray) {
            buf.write(bytes)
            if (buf.size() > maxBytes) {
                val cur = buf.toByteArray()
                buf.reset()
                buf.write(cur, cur.size - maxBytes, maxBytes)
            }
        }

        @Synchronized fun getBytes(): ByteArray = buf.toByteArray()
    }

    data class SessionState(
        val config: ConnectionConfig,
        val session: SshSession,
        var readJob: Job? = null,
        var status: ConnectionStatus = ConnectionStatus.CONNECTING,
        val outputBuffer: OutputBuffer = OutputBuffer(),
        var dataListener: ((ByteArray) -> Unit)? = null,
        var statusListener: ((ConnectionStatus) -> Unit)? = null,
        var passwordPrompter: PasswordPrompterFn? = null
    )

    // ── Internal state ────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sessions = mutableMapOf<String, SessionState>()
    private val _tabs = mutableListOf<TabInfo>()

    val tabs: List<TabInfo> get() = _tabs.toList()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // intentionally do nothing – keep sessions alive
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        sessions.values.forEach { it.session.disconnect() }
        sessions.clear()
        super.onDestroy()
    }

    // ── Tab management ────────────────────────────────────────────────────────

    fun addTab(tab: TabInfo) {
        if (_tabs.none { it.id == tab.id }) _tabs.add(tab)
    }

    fun removeTab(tabId: String) {
        disconnectSession(tabId)
        _tabs.removeAll { it.id == tabId }
        refreshNotification()
    }

    fun renameTab(tabId: String, newTitle: String) {
        _tabs.find { it.id == tabId }?.title = newTitle
    }

    fun clearTabs() {
        _tabs.map { it.id }.forEach { disconnectSession(it) }
        _tabs.clear()
        refreshNotification()
    }

    // ── Session management ────────────────────────────────────────────────────

    fun connect(
        tabId: String,
        config: ConnectionConfig,
        jumpConfig: ConnectionConfig?,
        cols: Int,
        rows: Int
    ) {
        disconnectSession(tabId)

        val session = SshSession(config, jumpConfig)
        val state = SessionState(config = config, session = session)
        sessions[tabId] = state

        session.passwordPrompter = { displayHost, callback ->
            state.passwordPrompter?.invoke(displayHost, callback) ?: callback(null)
        }

        val initMsg = if (jumpConfig == null) "Csatlakozás: ${config.host}:${config.port}...\r\n" else ""
        if (initMsg.isNotEmpty()) emitData(state, initMsg.toByteArray())

        serviceScope.launch {
            session.connect(
                termCols = cols,
                termRows = rows,
                onProgress = { msg -> emitData(state, msg.toByteArray()) },
                onConnected = {
                    updateStatus(tabId, ConnectionStatus.CONNECTED)
                    refreshNotification()
                    startReading(tabId)
                },
                onError = { err ->
                    updateStatus(tabId, ConnectionStatus.DISCONNECTED)
                    refreshNotification()
                    emitData(state, "Hiba: ${friendlyError(err)}\r\n".toByteArray())
                }
            )
        }
    }

    fun disconnectSession(tabId: String) {
        val state = sessions.remove(tabId) ?: return
        state.readJob?.cancel()
        state.session.disconnect()
        state.statusListener?.invoke(ConnectionStatus.DISCONNECTED)
        refreshNotification()
    }

    fun sendBytes(tabId: String, bytes: ByteArray) {
        sessions[tabId]?.session?.sendBytes(bytes)
    }

    fun resize(tabId: String, cols: Int, rows: Int) {
        sessions[tabId]?.session?.resize(cols, rows)
    }

    fun getStatus(tabId: String): ConnectionStatus =
        sessions[tabId]?.status ?: ConnectionStatus.NONE

    fun getBuffer(tabId: String): ByteArray =
        sessions[tabId]?.outputBuffer?.getBytes() ?: byteArrayOf()

    fun setDataListener(tabId: String, listener: ((ByteArray) -> Unit)?) {
        sessions[tabId]?.dataListener = listener
    }

    fun setStatusListener(tabId: String, listener: ((ConnectionStatus) -> Unit)?) {
        sessions[tabId]?.statusListener = listener
    }

    fun setPasswordPrompter(tabId: String, prompter: PasswordPrompterFn?) {
        sessions[tabId]?.passwordPrompter = prompter
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun emitData(state: SessionState, bytes: ByteArray) {
        state.outputBuffer.append(bytes)
        state.dataListener?.invoke(bytes)
    }

    private fun updateStatus(tabId: String, status: ConnectionStatus) {
        val state = sessions[tabId] ?: return
        state.status = status
        state.statusListener?.invoke(status)
    }

    private fun startReading(tabId: String) {
        val state = sessions[tabId] ?: return
        state.readJob = serviceScope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            val stream = state.session.inputStream ?: return@launch
            try {
                while (isActive && state.session.isConnected) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        state.outputBuffer.append(copy)
                        launch(Dispatchers.Main) { state.dataListener?.invoke(copy) }
                    }
                }
            } catch (_: Exception) {}
            launch(Dispatchers.Main) {
                updateStatus(tabId, ConnectionStatus.DISCONNECTED)
                refreshNotification()
                val msg = "\r\n[Kapcsolat lezárva]\r\n".toByteArray()
                state.outputBuffer.append(msg)
                state.dataListener?.invoke(msg)
            }
        }
    }

    private fun refreshNotification() {
        val count = sessions.values.count { it.status == ConnectionStatus.CONNECTED }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIF_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): Notification {
        val text = when (count) {
            0    -> "Fut a háttérben"
            1    -> "1 aktív SSH kapcsolat"
            else -> "$count aktív SSH kapcsolat"
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val channelId = if (count > 0) CHANNEL_ID_ACTIVE else CHANNEL_ID_IDLE
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KonsoleSSH")
            .setContentText(text)
            .setNumber(count)
            .setBadgeIconType(if (count > 0) NotificationCompat.BADGE_ICON_SMALL else NotificationCompat.BADGE_ICON_NONE)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_IDLE, "SSH háttérszolgáltatás", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Fut a háttérben, nincs aktív kapcsolat"; setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_ACTIVE, "SSH aktív kapcsolat", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Aktív SSH kapcsolatok életben tartása"; setShowBadge(true) }
        )
    }

    private fun friendlyError(err: Throwable): String {
        val parts = buildList {
            var t: Throwable? = err
            while (t != null) { t.message?.let { add(it) }; t = t.cause }
        }
        val full = parts.joinToString(" ").lowercase()
        return when {
            "connection refused"     in full -> "A kapcsolat elutasítva — a szerver nem fogad kapcsolatot ezen a porton."
            "connection timed out"   in full ||
            "connect timed out"      in full ||
            "timed out"              in full ||
            "timeout"                in full -> "Időtúllépés — a szerver nem válaszol."
            "no route to host"       in full -> "Nem érhető el a szerver — ellenőrizd a hálózatot és az IP-t."
            "network is unreachable" in full ||
            "unreachable"            in full -> "A hálózat nem érhető el."
            "unknown host"           in full ||
            "nodename nor servname"  in full -> "Ismeretlen hostnév — DNS hiba vagy elgépelés."
            "auth fail"              in full ||
            "authentication"         in full -> "Hitelesítés sikertelen — helytelen jelszó vagy kulcs."
            "userauth fail"          in full -> "Hitelesítés sikertelen — a szerver visszautasította."
            "connection is closed"   in full ||
            "closed by foreign host" in full -> "A kapcsolat váratlanul lezárult."
            "broken pipe"            in full -> "A kapcsolat megszakadt (broken pipe)."
            "port forwarding"        in full -> "Port forwarding hiba — a jump szerver nem engedélyezi."
            "channel"                in full -> "SSH csatorna hiba — a szerver lezárta a munkamenetet."
            else -> parts.firstOrNull()
                ?.replace(Regex("^session\\.connect:\\s*"), "")
                ?.replace(Regex("^[a-zA-Z]+(\\.[a-zA-Z]+)+:\\s*"), "")
                ?: err.javaClass.simpleName
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val CHANNEL_ID_IDLE   = "ssh_idle"
        private const val CHANNEL_ID_ACTIVE = "ssh_active"
        private const val NOTIF_ID          = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SshForegroundService::class.java))
        }

        fun createConnection(context: Context, onConnected: (SshForegroundService) -> Unit): ServiceConnection {
            return object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    onConnected((binder as LocalBinder).getService())
                }
                override fun onServiceDisconnected(name: ComponentName) {}
            }
        }
    }
}
