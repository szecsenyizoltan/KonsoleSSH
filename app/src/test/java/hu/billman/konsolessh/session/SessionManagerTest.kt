package hu.billman.konsolessh.session

import com.jcraft.jsch.JSchException
import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ssh.SessionPrompter
import hu.billman.konsolessh.ssh.SshError
import hu.billman.konsolessh.ssh.TerminalSession
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A munkamenet-kezelő viselkedése hálózat nélkül, hamis munkamenettel. Valódi
 * szálakon fut, mert a host key / jelszó kérdés a JSch-szálat blokkolja.
 */
class SessionManagerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class FakeSession(
        private val prompter: SessionPrompter,
        private val failWith: Exception?,
        private val askHostKey: Boolean,
    ) : TerminalSession {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val sent = LinkedBlockingQueue<ByteArray>()

        @Volatile var connected = false

        @Volatile var disconnected = false

        override val isConnected get() = connected && !disconnected

        override suspend fun connect(cols: Int, rows: Int, onProgress: suspend (String) -> Unit) {
            onProgress("progress\r\n")
            if (askHostKey && !prompter.confirmHostKey(HostKeyPrompt("srv", "ssh-ed25519", "SHA256:x", false))) {
                throw JSchException("reject HostKey: srv")
            }
            failWith?.let { throw it }
            connected = true
        }

        override fun read(buffer: ByteArray): Int {
            var chunk: ByteArray? = null
            while (!disconnected && chunk == null) chunk = incoming.poll(POLL_MS, TimeUnit.MILLISECONDS)
            val data = chunk?.takeIf { it.isNotEmpty() } ?: return -1
            data.copyInto(buffer)
            return data.size
        }

        override fun send(bytes: ByteArray) {
            sent.add(bytes)
        }

        override fun resize(cols: Int, rows: Int) = Unit
        override suspend fun upload(
            input: InputStream,
            remoteName: String,
            size: Long,
            onProgress: (Long, Long) -> Unit,
        ) = Unit
        override suspend fun deleteRemote(path: String) = Unit

        override fun disconnect() {
            disconnected = true
        }
    }

    private val gateway = ConnectionConfig(id = "gw", name = "gw", host = "gw.example.com", username = "u")
    private val target = ConnectionConfig(
        id = "vm",
        name = "vm",
        host = "10.0.0.5",
        username = "u",
        jumpConnectionId = "gw",
    )
    private val direct = ConnectionConfig(id = "d", name = "direct", host = "srv", username = "u", password = "secret")

    private var failWith: Exception? = null
    private var askHostKey = false
    private val created = LinkedBlockingQueue<Pair<FakeSession, List<ConnectionConfig>>>()
    private val repository = FakeConnectionRepository(listOf(gateway, target, direct))

    private val manager = SessionManager(
        repository,
        TerminalSessionFactory { _, jumps, prompter ->
            FakeSession(prompter, failWith, askHostKey).also { created.add(it to jumps) }
        },
        object : TerminalMessages {
            override fun connecting(host: String, port: Int) = "Connecting $host\r\n"
            override fun jumpProgress(host: String, port: Int) = "Jump $host\r\n"
            override fun jumpOk(host: String, port: Int) = "Jump ok $host\r\n"
        },
        scope,
    )

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting: $message" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun tab(id: String) = manager.state.value.tab(id)

    private fun output(id: String) = String(manager.snapshot(id)!!.bytes)

    private fun nextSession() = created.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)!!

    @Test
    fun `open tab keeps only the connection id and display data`() {
        val id = manager.openTab(direct)
        val tab = tab(id)!!
        assertEquals("d", tab.connectionId)
        assertEquals("direct", tab.title)
        assertEquals("srv", tab.host)
        assertEquals(ConnectionStatus.NONE, tab.status)
    }

    @Test
    fun `connect streams output and the remote close disconnects without error`() {
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        val (session, jumps) = nextSession()
        assertTrue(jumps.isEmpty())
        await("connected") { tab(id)?.status == ConnectionStatus.CONNECTED }

        session.incoming.add("hello".toByteArray())
        await("output") { output(id).endsWith("hello") }
        assertEquals("Connecting srv\r\nprogress\r\nhello", output(id))

        session.incoming.add(ByteArray(0))
        await("disconnected") { tab(id)?.status == ConnectionStatus.DISCONNECTED }
        assertNull(tab(id)?.error)
        assertTrue(session.disconnected)
    }

    @Test
    fun `jump chain comes from the repository and no connecting line is written`() {
        val id = manager.openTab(target)
        manager.connect(id, 80, 24)
        val (_, jumps) = nextSession()
        assertEquals(listOf("gw"), jumps.map { it.id })
        await("connected") { tab(id)?.status == ConnectionStatus.CONNECTED }
        assertEquals("progress\r\n", output(id))
    }

    @Test
    fun `connect failure ends disconnected with a typed error`() {
        failWith = JSchException("Auth fail for methods 'password'")
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        await("failed") { tab(id)?.error != null }
        assertEquals(ConnectionStatus.DISCONNECTED, tab(id)?.status)
        assertEquals(SshError.AuthFailed, tab(id)?.error)

        manager.clearError(id)
        assertNull(tab(id)?.error)
    }

    @Test
    fun `deleted connection and unreadable store are reported, not thrown`() {
        val missing = manager.openTab(ConnectionConfig(id = "gone", host = "h", username = "u"))
        manager.connect(missing, 80, 24)
        await("missing") { tab(missing)?.error != null }
        assertEquals(SshError.ConnectionMissing, tab(missing)?.error)

        repository.unreadable = true
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        await("unreadable") { tab(id)?.error != null }
        assertEquals(SshError.StoreUnreadable, tab(id)?.error)
    }

    @Test
    fun `host key question appears in state and the answer resumes the connection`() {
        askHostKey = true
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        await("prompt") { tab(id)?.prompt != null }
        val prompt = tab(id)!!.prompt as PendingPrompt.HostKey
        assertEquals("srv", prompt.prompt.host)
        assertEquals(ConnectionStatus.CONNECTING, tab(id)?.status)

        manager.answerPrompt(id, prompt.id, PromptAnswer.HostKey(true))
        await("connected") { tab(id)?.status == ConnectionStatus.CONNECTED }
        assertNull(tab(id)?.prompt)
    }

    @Test
    fun `rejected host key is a typed error`() {
        askHostKey = true
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        await("prompt") { tab(id)?.prompt != null }
        manager.answerPrompt(id, tab(id)!!.prompt!!.id, PromptAnswer.HostKey(false))
        await("rejected") { tab(id)?.error != null }
        assertEquals(SshError.HostKeyRejected, tab(id)?.error)
    }

    @Test
    fun `send reaches the session and close tab disconnects and removes it`() {
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        val (session, _) = nextSession()
        await("connected") { tab(id)?.status == ConnectionStatus.CONNECTED }

        manager.send(id, "ls\n".toByteArray())
        assertEquals("ls\n", String(session.sent.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)!!))

        manager.closeTab(id)
        assertNull(tab(id))
        await("disconnected") { session.disconnected }
    }

    @Test
    fun `connect is ignored while already connected`() {
        val id = manager.openTab(direct)
        manager.connect(id, 80, 24)
        nextSession()
        await("connected") { tab(id)?.status == ConnectionStatus.CONNECTED }
        manager.connect(id, 80, 24)
        Thread.sleep(POLL_MS * 5)
        assertNull(created.poll())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
