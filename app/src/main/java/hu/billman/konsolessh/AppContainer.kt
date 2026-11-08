package hu.billman.konsolessh

import android.content.Context
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.CryptoBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository
import hu.billman.konsolessh.data.PrefsSettingsRepository
import hu.billman.konsolessh.data.SettingsRepository
import hu.billman.konsolessh.diagnostics.AndroidProblemReports
import hu.billman.konsolessh.diagnostics.ProblemReports
import hu.billman.konsolessh.session.SessionManager
import hu.billman.konsolessh.session.TerminalMessages
import hu.billman.konsolessh.ssh.HostKeyStore
import hu.billman.konsolessh.ssh.SshKeyProvisioner
import hu.billman.konsolessh.ssh.SshSession
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manuális dependency-gráf (composition root). Egyetlen példány él az
 * Application alatt; az osztályok konstruktoron kapják a függőségeiket, a
 * konténert csak az Android által példányosított komponensek (Activity,
 * Service, ViewModel-factory) érik el, egy ponton.
 */
class AppContainer(
    context: Context,
    /** Instrumentált tesztekhez: hálózat nélküli munkamenetek. */
    sessionFactoryOverride: TerminalSessionFactory? = null,
    problemReportsOverride: ProblemReports? = null,
) {

    private val appContext: Context = context.applicationContext

    /** A munkamenetek az alkalmazással élnek, nem egy Activity-vel. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Lazy, so the Keystore is not touched before first use (tests, Robolectric).
    private val cryptoBox: CryptoBox by lazy { AesGcmKeystoreBox() }

    val connectionRepository: ConnectionRepository by lazy {
        CryptoBoxConnectionRepository(appContext, cryptoBox)
    }

    val settingsRepository: SettingsRepository by lazy { PrefsSettingsRepository(appContext) }

    // One instance, so parallel tabs do not overwrite each other's entries.
    val hostKeyStore: HostKeyStore by lazy {
        HostKeyStore(File(appContext.filesDir, "known_hosts"))
    }

    val keyProvisioner: SshKeyProvisioner by lazy { SshKeyProvisioner(hostKeyStore) }

    private val androidProblemReports by lazy { AndroidProblemReports(appContext) }
    val problemReports: ProblemReports = problemReportsOverride ?: androidProblemReports

    /** Ide írja a nem kezelt kivételt a [hu.billman.konsolessh.diagnostics.CrashRecorder]. */
    val crashFile: File get() = androidProblemReports.crashFile

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
