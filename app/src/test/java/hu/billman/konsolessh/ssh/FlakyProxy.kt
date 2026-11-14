package hu.billman.konsolessh.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP relay between the client and a server, for fault injection: [cut]
 * severs every live connection at once, as if the network dropped (a
 * Wi-Fi to mobile switch, a NAT timeout). It still accepts new connections
 * afterwards.
 */
class FlakyProxy(private val targetPort: Int) : AutoCloseable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "flaky-proxy-accept") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                connect(client)
            }
        }
    }

    private fun connect(client: Socket) {
        val upstream = runCatching { Socket(InetAddress.getLoopbackAddress(), targetPort) }.getOrNull()
        if (upstream == null) {
            client.close()
            return
        }
        sockets += client
        sockets += upstream
        pump(client.getInputStream(), upstream.getOutputStream(), client, upstream)
        pump(upstream.getInputStream(), client.getOutputStream(), client, upstream)
    }

    /** Severs every live connection at once. */
    fun cut() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    private fun pump(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        thread(isDaemon = true, name = "flaky-proxy-pump") {
            val buf = ByteArray(BUFFER)
            runCatching {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            }
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    override fun close() {
        runCatching { server.close() }
        cut()
    }

    private companion object {
        const val BACKLOG = 16
        const val BUFFER = 8192
    }
}
