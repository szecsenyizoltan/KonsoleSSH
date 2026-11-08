package hu.billman.konsolessh.session

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ssh.FlakyProxy
import hu.billman.konsolessh.ssh.HostKeyStore
import hu.billman.konsolessh.ssh.SshSession
import hu.billman.konsolessh.ssh.SshTestServers
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Hibainjektálás valódi SSH-val: a [SessionManager] valódi [SshSession]-ökkel
 * dolgozik, a hálózatot egy [FlakyProxy] vágja el. A host key kérdésekre egy
 * automata „felhasználó” válaszol a kezelő állapotából, mint a felület.
 */
class SessionFaultInjectionTest {

    private lateinit var servers: SshTestServers
    private lateinit var proxy: FlakyProxy
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var manager: SessionManager
    private lateinit var target: ConnectionConfig

    @Before
    fun setUp() {
        servers = SshTestServers()
        proxy = FlakyProxy(servers.port("vm"))
        target = servers.config("vm").copy(port = proxy.port)
        val knownHosts = HostKeyStore(File(servers.dir, "known_hosts"))
        manager = SessionManager(
            FakeConnectionRepository(listOf(target)),
            TerminalSessionFactory { config, jumps, prompter -> SshSession(config, knownHosts, jumps, prompter) },
            object : TerminalMessages {
                override fun connecting(host: String, port: Int) = ""
                override fun jumpProgress(host: String, port: Int) = ""
                override fun jumpOk(host: String, port: Int) = ""
            },
            scope,
        )
        // The "user": accepts every host key question the UI would show.
        manager.state.onEach { state ->
            state.tabs.forEach { tab ->
                val prompt = tab.prompt as? PendingPrompt.HostKey ?: return@forEach
                manager.answerPrompt(tab.id, prompt.id, PromptAnswer.HostKey(true))
            }
        }.launchIn(scope)
    }

    @After
    fun tearDown() {
        if (::manager.isInitialized) manager.closeAll()
        scope.cancel()
        if (::proxy.isInitialized) proxy.close()
        if (::servers.isInitialized) servers.close()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: $what (${manager.state.value.tabs})")
            Thread.sleep(POLL_MS)
        }
    }

    private fun status(id: String) = manager.state.value.tab(id)?.status

    private fun output(id: String) = String(manager.snapshot(id)?.bytes ?: ByteArray(0))

    private fun connected(id: String) {
        await("tab connected") { status(id) == ConnectionStatus.CONNECTED }
        await("shell output") { SshTestServers.MARKER in output(id) }
    }

    private fun assertNothingLeft() {
        await("no connection left on the server") { servers.established("vm") == 0 }
        await("remote shell gone") { !servers.shellAlive() }
    }

    @Test
    fun `a network cut ends the session as disconnected, without an error`() {
        val id = manager.openTab(target)
        manager.connect(id, COLS, ROWS)
        connected(id)

        proxy.cut()

        await("disconnected after the cut") { status(id) == ConnectionStatus.DISCONNECTED }
        assertNull(manager.state.value.tab(id)?.error)
        assertNothingLeft()
    }

    @Test
    fun `reconnect after a cut works and gets a fresh shell`() {
        val id = manager.openTab(target)
        manager.connect(id, COLS, ROWS)
        connected(id)
        proxy.cut()
        await("disconnected") { status(id) == ConnectionStatus.DISCONNECTED }

        val before = output(id).split(SshTestServers.MARKER).size
        manager.connect(id, COLS, ROWS, isReconnect = true)
        await("reconnected") { status(id) == ConnectionStatus.CONNECTED }
        await("second shell") { output(id).split(SshTestServers.MARKER).size > before }
        manager.closeTab(id)
        assertNothingLeft()
    }

    @Test
    fun `parallel tabs connect independently and close cleanly`() {
        val ids = List(TABS) { manager.openTab(target) }
        ids.forEach { manager.connect(it, COLS, ROWS) }
        ids.forEach(::connected)
        assertEquals(TABS, manager.state.value.connectedCount)

        manager.send(ids[1], "only-tab-1\n".toByteArray())
        await("echo in tab 1") { "only-tab-1" in output(ids[1]) }
        assertEquals(false, "only-tab-1" in output(ids[0]))

        manager.closeAll()
        assertNothingLeft()
    }

    @Test
    fun `opening and closing tabs quickly leaves nothing behind`() {
        repeat(RAPID_ROUNDS) {
            val id = manager.openTab(target)
            manager.connect(id, COLS, ROWS)
            Thread.sleep(it * RAPID_STEP_MS)
            manager.closeTab(id)
        }
        assertEquals(0, manager.state.value.tabs.size)
        assertNothingLeft()
    }

    private companion object {
        const val COLS = 80
        const val ROWS = 24
        const val TABS = 3
        const val RAPID_ROUNDS = 8
        const val RAPID_STEP_MS = 40L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
    }
}
