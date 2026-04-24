package hu.billman.konsolessh.ssh

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
import hu.billman.konsolessh.R
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ui.ConnectionStatus
import hu.billman.konsolessh.ui.TabInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    class SessionState(
        val config: ConnectionConfig,
        val session: SshSession,
        var readJob: Job? = null,
        var status: ConnectionStatus = ConnectionStatus.CONNECTING,
        val outputBuffer: OutputBuffer = OutputBuffer(),
        var dataListener: ((ByteArray) -> Unit)? = null,
        var statusListener: ((ConnectionStatus) -> Unit)? = null,
        var passwordPrompter: PasswordPrompterFn? = null,
        var connectErrorListener: ((String) -> Unit)? = null,
    ) {
        /**
         * Flow-alapú esemény-stream. Helyettesítendő a régi callback-slotokat
         * (dataListener / statusListener / connectErrorListener). A password-
         * prompter külön marad, mert request/response szemantikájú.
         */
        val events: MutableSharedFlow<SessionEvent> = MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

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

        val session = SshSession(
            config, jumpConfig,
            SshSession.Messages(
                jumpProgress = { h, p -> getString(R.string.terminal_jump_progress, h, p) },
                jumpOk       = { h, p -> getString(R.string.terminal_jump_ok, h, p) }
            )
        )
        val state = SessionState(config = config, session = session)
        sessions[tabId] = state

        session.passwordPrompter = { displayHost, callback ->
            state.passwordPrompter?.invoke(displayHost, callback) ?: callback(null)
        }

        val initMsg = if (jumpConfig == null)
            getString(R.string.terminal_connecting_host, config.host, config.port) else ""
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
                    val friendly = friendlyError(err)
                    state.events.tryEmit(SessionEvent.ConnectError(friendly))
                    val listener = state.connectErrorListener
                    if (listener != null) {
                        // UI wants to show the error itself (toast + auto-close).
                        listener.invoke(friendly)
                    } else {
                        // No listener attached (fragment detached) — fall back to
                        // writing the error to the terminal so it is not lost.
                        emitData(state, getString(R.string.error_generic, friendly).toByteArray())
                    }
                }
            )
        }
    }

    fun disconnectSession(tabId: String) {
        val state = sessions.remove(tabId) ?: return
        state.readJob?.cancel()
        state.session.disconnect()
        state.events.tryEmit(SessionEvent.StatusChange(ConnectionStatus.DISCONNECTED))
        state.statusListener?.invoke(ConnectionStatus.DISCONNECTED)
        refreshNotification()
    }

    fun sendBytes(tabId: String, bytes: ByteArray) {
        sessions[tabId]?.session?.sendBytes(bytes)
    }

    /** Upload a local file to the remote home over SFTP with progress. */
    fun uploadFile(
        tabId: String,
        uri: android.net.Uri,
        remoteName: String,
        onProgress: (transferred: Long, total: Long) -> Unit,
        onDone: (remoteDir: String?, error: Throwable?) -> Unit
    ) {
        val state = sessions[tabId] ?: run {
            onDone(null, IllegalStateException("No active session for tab")); return
        }
        serviceScope.launch {
            try {
                val size = queryFileSize(uri) ?: -1L
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        state.session.upload(input, remoteName, size, onProgress)
                    } ?: throw java.io.IOException("Cannot open file: $uri")
                }
                onDone("~", null)
            } catch (e: Throwable) {
                onDone(null, e)
            }
        }
    }

    /** Delete a file from the remote side (e.g. to undo an upload). */
    fun deleteRemoteFile(
        tabId: String,
        remotePath: String,
        onDone: (error: Throwable?) -> Unit
    ) {
        val state = sessions[tabId] ?: run {
            onDone(IllegalStateException("No active session for tab")); return
        }
        serviceScope.launch {
            try {
                state.session.deleteRemote(remotePath)
                onDone(null)
            } catch (e: Throwable) {
                onDone(e)
            }
        }
    }

    private fun queryFileSize(uri: android.net.Uri): Long? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) {
                val s = c.getLong(idx)
                if (s > 0) return s
            }
        }
        return null
    }

    fun resize(tabId: String, cols: Int, rows: Int) {
        sessions[tabId]?.session?.resize(cols, rows)
    }

    fun getStatus(tabId: String): ConnectionStatus =
        sessions[tabId]?.status ?: ConnectionStatus.NONE

    fun getBuffer(tabId: String): ByteArray =
        sessions[tabId]?.outputBuffer?.getBytes() ?: byteArrayOf()

    /**
     * Flow-alapú esemény-stream a session-re feliratkozáshoz (data-bájtok,
     * státuszváltozás, kapcsolati hiba). Null, ha nincs ilyen session.
     *
     * A UI-oldal `repeatOnLifecycle(STARTED).collect { … }`-ban kezelje,
     * így Fragment-detach esetén automatikusan felszabadul a megfigyelés.
     */
    fun events(tabId: String): SharedFlow<SessionEvent>? =
        sessions[tabId]?.events?.asSharedFlow()

    @Deprecated(
        message = "Use events(tabId) SharedFlow with collect { if (it is SessionEvent.Data) … } in the UI layer.",
        replaceWith = ReplaceWith("events(tabId)"),
    )
    fun setDataListener(tabId: String, listener: ((ByteArray) -> Unit)?) {
        sessions[tabId]?.dataListener = listener
    }

    @Deprecated(
        message = "Use events(tabId) SharedFlow with collect { if (it is SessionEvent.StatusChange) … } in the UI layer.",
        replaceWith = ReplaceWith("events(tabId)"),
    )
    fun setStatusListener(tabId: String, listener: ((ConnectionStatus) -> Unit)?) {
        sessions[tabId]?.statusListener = listener
    }

    fun setPasswordPrompter(tabId: String, prompter: PasswordPrompterFn?) {
        sessions[tabId]?.passwordPrompter = prompter
    }

    @Deprecated(
        message = "Use events(tabId) SharedFlow with collect { if (it is SessionEvent.ConnectError) … } in the UI layer.",
        replaceWith = ReplaceWith("events(tabId)"),
    )
    fun setConnectErrorListener(tabId: String, listener: ((String) -> Unit)?) {
        sessions[tabId]?.connectErrorListener = listener
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun emitData(state: SessionState, bytes: ByteArray) {
        state.outputBuffer.append(bytes)
        state.events.tryEmit(SessionEvent.Data(bytes))
        state.dataListener?.invoke(bytes)
    }

    private fun updateStatus(tabId: String, status: ConnectionStatus) {
        val state = sessions[tabId] ?: return
        state.status = status
        state.events.tryEmit(SessionEvent.StatusChange(status))
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
                        state.events.tryEmit(SessionEvent.Data(copy))
                        launch(Dispatchers.Main) { state.dataListener?.invoke(copy) }
                    }
                }
            } catch (_: Exception) {}
            launch(Dispatchers.Main) {
                updateStatus(tabId, ConnectionStatus.DISCONNECTED)
                refreshNotification()
                val msg = getString(R.string.terminal_connection_closed).toByteArray()
                state.outputBuffer.append(msg)
                state.events.tryEmit(SessionEvent.Data(msg))
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
            0    -> getString(R.string.service_idle)
            1    -> getString(R.string.service_one_active)
            else -> getString(R.string.service_n_active, count)
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
            NotificationChannel(CHANNEL_ID_IDLE, getString(R.string.channel_idle_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_idle_desc); setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_ACTIVE, getString(R.string.channel_active_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_active_desc); setShowBadge(true) }
        )
    }

    private fun friendlyError(err: Throwable): String {
        val parts = buildList {
            var t: Throwable? = err
            while (t != null) { t.message?.let { add(it) }; t = t.cause }
        }
        val full = parts.joinToString(" ").lowercase()
        return when {
            "connection refused"     in full -> getString(R.string.err_connection_refused)
            "connection timed out"   in full ||
            "connect timed out"      in full ||
            "timed out"              in full ||
            "timeout"                in full -> getString(R.string.err_timeout)
            "no route to host"       in full -> getString(R.string.err_no_route)
            "network is unreachable" in full ||
            "unreachable"            in full -> getString(R.string.err_network_unreachable)
            "unknown host"           in full ||
            "nodename nor servname"  in full -> getString(R.string.err_unknown_host)
            "bad credentials"        in full -> getString(R.string.err_bad_credentials)
            "algorithm negotiation"  in full -> getString(R.string.err_algo_mismatch)
            "auth cancel"            in full -> getString(R.string.err_auth_cancel)
            "auth fail"              in full ||
            "authentication"         in full -> getString(R.string.err_auth_failed)
            "userauth fail"          in full -> getString(R.string.err_userauth_failed)
            "connection is closed"   in full ||
            "closed by foreign host" in full -> getString(R.string.err_conn_closed)
            "broken pipe"            in full -> getString(R.string.err_broken_pipe)
            "port forwarding"        in full -> getString(R.string.err_port_forwarding)
            "channel"                in full -> getString(R.string.err_channel)
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
