package hu.billman.konsolessh

import android.content.Context
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.CryptoBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository
import hu.billman.konsolessh.data.DataStoreSettingsRepository
import hu.billman.konsolessh.data.ProblemReportLeftovers
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.session.SessionManager
import hu.billman.konsolessh.session.TerminalMessages
import hu.billman.konsolessh.ssh.ConnectionSetup
import hu.billman.konsolessh.ssh.HostKeyStore
import hu.billman.konsolessh.ssh.SshKeyGenerator
import hu.billman.konsolessh.ssh.SshKeyProvisioner
import hu.billman.konsolessh.ssh.SshSession
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import hu.billman.konsolessh.sync.CryptoBoxSyncKeyStorage
import hu.billman.konsolessh.sync.DriveAppDataStore
import hu.billman.konsolessh.sync.DriveAuthorizer
import hu.billman.konsolessh.sync.SyncManager
import hu.billman.konsolessh.sync.SyncTriggeringRepository
import hu.billman.konsolessh.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The hand-written dependency graph (composition root). One instance lives
 * in the Application. Classes get their dependencies through their
 * constructors; only the components Android instantiates (activities, the
 * service, the ViewModel factory) reach the container, each at one point.
 */
class AppContainer(
    context: Context,
    /** For instrumented tests: sessions without a network. */
    sessionFactoryOverride: TerminalSessionFactory? = null,
) {

    private val appContext: Context = context.applicationContext

    /** The sessions live as long as the application, not as long as an activity. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Lazy, so the Keystore is not touched before first use (tests, Robolectric).
    private val cryptoBox: CryptoBox by lazy { AesGcmKeystoreBox() }

    // Disk work for small stores; outlives every screen, like the sessions.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The store itself; sync writes through it, everything else through connectionRepository.
    private val connectionStore: ConnectionRepository by lazy {
        CryptoBoxConnectionRepository.create(appContext, cryptoBox, ioScope)
    }

    val connectionRepository: ConnectionRepository by lazy {
        SyncTriggeringRepository(connectionStore) { syncManager.requestSync() }
    }

    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository.create(appContext, ioScope) }

    // One instance, so parallel tabs do not overwrite each other's entries.
    val hostKeyStore: HostKeyStore by lazy {
        HostKeyStore(File(appContext.filesDir, "known_hosts"), onChange = { syncManager.requestSync() })
    }

    val driveAuthorizer: DriveAuthorizer by lazy { DriveAuthorizer(appContext) }

    val syncManager: SyncManager by lazy {
        SyncManager(
            connections = connectionStore,
            hostKeys = hostKeyStore,
            keys = CryptoBoxSyncKeyStorage(File(appContext.filesDir, "sync_key.bin"), cryptoBox),
            remote = DriveAppDataStore(driveAuthorizer::accessToken, driveAuthorizer::forget),
            scope = ioScope,
            scheduler = WorkManagerSyncScheduler(appContext),
        )
    }

    /**
     * The app came to the foreground, at start too: if sync is on, one round
     * at once, so changes made on the other devices meanwhile arrive.
     */
    fun onAppForeground() {
        ioScope.launch {
            runCatching {
                syncManager.start()
                syncManager.requestSync(now = true)
            }
        }
    }

    /** The app went to the background: a change still waiting to upload goes now. */
    fun onAppBackground() = syncManager.flush()

    val connectionSetup: ConnectionSetup by lazy {
        ConnectionSetup(connectionRepository, SshKeyProvisioner(hostKeyStore), SshKeyGenerator)
    }

    /** Removes what older versions left on the device and no version reads any more. */
    fun deleteLeftovers() {
        ioScope.launch { ProblemReportLeftovers.delete(appContext) }
    }

    private val terminalMessages: TerminalMessages by lazy { AndroidTerminalMessages(appContext) }

    private val sessionFactory = sessionFactoryOverride ?: TerminalSessionFactory { config, jumps, prompter ->
        SshSession(
            config,
            hostKeyStore,
            jumps,
            prompter,
            SshSession.Messages(
                jumpProgress = terminalMessages::jumpProgress,
                jumpOk = terminalMessages::jumpOk,
            ),
        )
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(connectionRepository, sessionFactory, terminalMessages, applicationScope)
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as KonsoleApplication).container
    }
}

private class AndroidTerminalMessages(private val context: Context) : TerminalMessages {
    override fun connecting(host: String, port: Int): String =
        context.getString(R.string.terminal_connecting_host, host, port)

    override fun jumpProgress(host: String, port: Int): String =
        context.getString(R.string.terminal_jump_progress, host, port)

    override fun jumpOk(host: String, port: Int): String =
        context.getString(R.string.terminal_jump_ok, host, port)
}
