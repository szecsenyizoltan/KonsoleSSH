package hu.billman.konsolessh

import com.jcraft.jsch.JSchException
import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ssh.SessionPrompter
import hu.billman.konsolessh.ssh.TerminalSession
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hálózat nélküli munkamenet az instrumentált tesztekhez: kapcsolódáskor
 * (ha [askHostKey]) host key kérdést tesz fel a valódi kérdés-útvonalon,
 * utána [GREETING]-et ír, és a bevitelt visszhangozza, mint egy pty.
 */
class FakeTerminalSession(private val prompter: SessionPrompter) : TerminalSession {

    private val incoming = LinkedBlockingQueue<ByteArray>()

    @Volatile private var connected = false

    @Volatile private var closed = false

    override val isConnected: Boolean get() = connected && !closed

    override suspend fun connect(cols: Int, rows: Int, onProgress: suspend (String) -> Unit) {
        withContext(Dispatchers.IO) {
            if (askHostKey && !prompter.confirmHostKey(PROMPT)) throw JSchException("reject HostKey: fake.example")
        }
        connected = true
        incoming.add(GREETING.toByteArray())
    }

    override fun read(buffer: ByteArray): Int {
        while (!closed) {
            val chunk = incoming.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            chunk.copyInto(buffer)
            return chunk.size
        }
        return -1
    }

    override fun send(bytes: ByteArray) {
        incoming.add(bytes)
    }

    override fun resize(cols: Int, rows: Int) = Unit

    override suspend fun upload(input: InputStream, remoteName: String, size: Long, onProgress: (Long, Long) -> Unit) =
        Unit

    override suspend fun deleteRemote(path: String) = Unit

    override fun disconnect() {
        closed = true
    }

    companion object {
        const val GREETING = "FAKE-GREETING\r\n"
        private const val POLL_MS = 50L
        private val PROMPT = HostKeyPrompt("fake.example", "ssh-ed25519", "SHA256:instrumented-test", changed = false)

        /** A következő kapcsolódás feltegye-e a host key kérdést. */
        @Volatile var askHostKey = true

        val factory = TerminalSessionFactory { _, _, prompter -> FakeTerminalSession(prompter) }
    }
}
