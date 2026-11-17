package hu.billman.konsolessh

import com.jcraft.jsch.JSchException
import hu.billman.konsolessh.ssh.FileTransfer
import hu.billman.konsolessh.ssh.HostKeyPrompt
import hu.billman.konsolessh.ssh.SessionPrompter
import hu.billman.konsolessh.ssh.TerminalSession
import hu.billman.konsolessh.ssh.TerminalSessionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A network-free session for the instrumented tests. On connect it asks
 * a host key question through the real prompt path (if [askHostKey]), then
 * writes [GREETING] and echoes input like a pty.
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

    override val fileTransfer: FileTransfer = object : FileTransfer {
        override suspend fun upload(
            input: InputStream,
            remoteName: String,
            size: Long,
            onProgress: (Long, Long) -> Unit,
        ) {
            onProgress(0, size)
            uploadGate?.await()
            uploaded.add(remoteName)
        }

        override suspend fun deleteRemote(path: String) = Unit
    }

    override fun disconnect() {
        closed = true
    }

    companion object {
        const val GREETING = "FAKE-GREETING\r\n"
        private const val POLL_MS = 50L
        private val PROMPT = HostKeyPrompt("fake.example", "ssh-ed25519", "SHA256:instrumented-test", changed = false)

        /** Whether the next connect asks the host key question. */
        @Volatile var askHostKey = true

        /** When set, an upload waits for it, so a test can act while the upload runs. */
        @Volatile var uploadGate: CompletableDeferred<Unit>? = null

        /** Remote names of the uploads that finished. */
        val uploaded: MutableList<String> = CopyOnWriteArrayList()

        val factory = TerminalSessionFactory { _, _, prompter -> FakeTerminalSession(prompter) }
    }
}
