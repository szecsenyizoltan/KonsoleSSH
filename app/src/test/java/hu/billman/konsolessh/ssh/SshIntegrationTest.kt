package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Az [SshSession] valódi OpenSSH szerverekkel ([SshTestServers]): host key
 * ellenőrzés, többlépcsős jump-lánc, bontás kapcsolódás közben, szerveroldali
 * bontás, SFTP a láncon át. A szivárgást a szerver oldaláról mérjük: élő
 * TCP-kapcsolatok és a shell folyamata.
 */
class SshIntegrationTest {

    private lateinit var servers: SshTestServers
    private lateinit var knownHosts: HostKeyStore
    private val reader = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        servers = SshTestServers()
        knownHosts = HostKeyStore(File(servers.dir, "known_hosts"))
    }

    @After
    fun tearDown() {
        reader.shutdownNow()
        if (::servers.isInitialized) servers.close()
    }

    private class Prompter(
        private val accept: Boolean = true,
        private val onHostKey: (HostKeyPrompt) -> Unit = {},
    ) : SessionPrompter {
        val hostKeys = CopyOnWriteArrayList<HostKeyPrompt>()
        override fun password(displayHost: String): String? = null
        override fun confirmHostKey(prompt: HostKeyPrompt): Boolean {
            hostKeys += prompt
            onHostKey(prompt)
            return accept
        }
    }

    private fun session(target: ConnectionConfig, jumps: List<ConnectionConfig> = emptyList(), prompter: SessionPrompter) =
        SshSession(target, knownHosts, jumps, prompter)

    private fun connect(session: SshSession) = runBlocking {
        withTimeout(TIMEOUT_MS) { session.connect(COLS, ROWS) {} }
    }

    /** A shell kimenete, amíg meg nem jelenik benne [expected] (vagy lejár az idő). */
    private fun readUntil(session: SshSession, expected: String): String {
        val out = StringBuilder()
        val done = reader.submit {
            val buf = ByteArray(BUFFER)
            while (expected !in out) {
                val n = session.read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n))
            }
        }
        runCatching { done.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return out.toString()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: $what")
            Thread.sleep(POLL_MS)
        }
    }

    private fun assertNothingLeft() {
        listOf("gw", "hv", "vm").forEach { name ->
            eventually("no connection left to $name") { servers.established(name) == 0 }
        }
        eventually("remote shell gone") { !servers.shellAlive() }
    }

    @Test
    fun `first connection asks for the host key once, later ones trust it`() {
        val first = Prompter()
        val s1 = session(servers.config("vm"), prompter = first)
        connect(s1)
        assertTrue(readUntil(s1, SshTestServers.MARKER).contains(SshTestServers.MARKER))
        assertEquals(1, first.hostKeys.size)
        assertFalse(first.hostKeys.single().changed)
        assertEquals("ssh-ed25519", first.hostKeys.single().keyType)
        s1.disconnect()

        val second = Prompter(accept = false)
        val s2 = session(servers.config("vm"), prompter = second)
        connect(s2)
        assertTrue(second.hostKeys.isEmpty())
        s2.disconnect()
        assertNothingLeft()
    }

    @Test
    fun `a changed host key is shown as changed and rejected`() {
        val s1 = session(servers.config("vm"), prompter = Prompter())
        connect(s1)
        s1.disconnect()
        servers.restartWithNewHostKey("vm")

        val prompter = Prompter(accept = false)
        val s2 = session(servers.config("vm"), prompter = prompter)
        val error = runCatching { connect(s2) }.exceptionOrNull()
        assertTrue("connect must fail", error != null)
        assertTrue(prompter.hostKeys.single().changed)
        assertTrue(SshErrorClassifier.classify(error!!) in setOf(SshError.HostKeyRejected, SshError.HostKeyChanged))
        assertNothingLeft()
    }

    @Test
    fun `two-hop jump chain reaches the target and checks every host by its real name`() {
        val gw = servers.config("gw")
        val hv = servers.config("hv", jump = gw)
        val vm = servers.config("vm", jump = hv)
        val prompter = Prompter()
        val s = session(vm, JumpChain.resolve(vm, listOf(gw, hv, vm)), prompter)
        connect(s)
        assertTrue(readUntil(s, SshTestServers.MARKER).contains(SshTestServers.MARKER))
        val expectedHosts = listOf("gw", "hv", "vm").map { "[127.0.0.1]:${servers.port(it)}" }
        assertEquals(expectedHosts, prompter.hostKeys.map { it.host })
        assertEquals(expectedHosts.toSet(), knownHosts.entries().map { it.host }.toSet())
        s.disconnect()
        assertNothingLeft()
    }

    @Test
    fun `disconnect while connecting leaves no connection and no shell behind`() {
        val gw = servers.config("gw")
        val hv = servers.config("hv", jump = gw)
        val vm = servers.config("vm", jump = hv)
        lateinit var s: SshSession
        // The tab is closed while the last host key question is open, then the
        // question is answered; the blocking connect still finishes its work.
        val prompter = Prompter { prompt -> if (prompt.host.endsWith(":${servers.port("vm")}")) s.disconnect() }
        s = session(vm, JumpChain.resolve(vm, listOf(gw, hv, vm)), prompter)
        val error = runCatching { connect(s) }.exceptionOrNull()
        assertTrue("connect must not report success after disconnect", error != null)
        assertFalse(s.isConnected)
        assertNothingLeft()
    }

    @Test
    fun `cancelling the connect coroutine leaves nothing behind`() = runBlocking {
        val gw = servers.config("gw")
        val vm = servers.config("vm", jump = gw)
        lateinit var job: kotlinx.coroutines.Deferred<Unit>
        val prompter = Prompter { prompt -> if (prompt.host.endsWith(":${servers.port("vm")}")) job.cancel() }
        val s = session(vm, JumpChain.resolve(vm, listOf(gw, vm)), prompter)
        job = async(Dispatchers.IO) { s.connect(COLS, ROWS) {} }
        runCatching { job.await() }
        assertFalse(s.isConnected)
        assertNothingLeft()
    }

    @Test
    fun `the remote side closing ends the read loop`() {
        val s = session(servers.config("vm"), prompter = Prompter())
        connect(s)
        readUntil(s, SshTestServers.MARKER)
        s.send(byteArrayOf(CTRL_D))
        val done = reader.submit<Int> {
            val buf = ByteArray(BUFFER)
            var n: Int
            do n = s.read(buf) while (n >= 0)
            n
        }
        assertEquals(-1, done.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        s.disconnect()
        assertNothingLeft()
    }

    @Test
    fun `sftp upload and delete go through the jump host`() = runBlocking {
        val gw = servers.config("gw")
        val vm = servers.config("vm", jump = gw)
        val s = session(vm, JumpChain.resolve(vm, listOf(gw, vm)), Prompter())
        connect(s)
        val payload = ByteArray(PAYLOAD_SIZE) { (it % 251).toByte() }
        val progress = CopyOnWriteArrayList<Long>()
        s.upload(ByteArrayInputStream(payload), "payload.bin", payload.size.toLong()) { done, _ -> progress += done }
        val stored = File(servers.uploadDir, "payload.bin")
        assertTrue(stored.readBytes().contentEquals(payload))
        assertEquals(payload.size.toLong(), progress.last())
        s.deleteRemote("payload.bin")
        assertFalse(stored.exists())
        s.disconnect()
        assertNothingLeft()
    }

    @Test
    fun `an unknown client key is an authentication failure`() {
        val other = File(servers.dir, "other_key")
        ProcessBuilder("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-m", "PEM", "-N", "", "-f", other.path)
            .start().waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val s = session(servers.config("vm").copy(privateKey = other.readText()), prompter = Prompter())
        val error = runCatching { connect(s) }.exceptionOrNull() ?: error("connect must fail")
        assertTrue(
            SshErrorClassifier.classify(error) in setOf(SshError.AuthFailed, SshError.UserAuthFailed, SshError.AuthCancelled),
        )
        assertNothingLeft()
    }

    private companion object {
        const val COLS = 80
        const val ROWS = 24
        const val BUFFER = 4096
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 50L
        const val CTRL_D: Byte = 4
        const val PAYLOAD_SIZE = 300_000
    }
}
