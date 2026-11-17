package hu.billman.konsolessh.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * [FileTransfer] over SFTP on an already open SSH session. Each call opens
 * its own channel and closes it afterwards, so a transfer never blocks the
 * shell channel of the same session.
 */
internal class SftpTransfer(private val session: () -> Session?) : FileTransfer {

    override suspend fun upload(
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    ) = withSftp { sftp ->
        val monitor = object : SftpProgressMonitor {
            private var total: Long = size.coerceAtLeast(0L)
            private var transferred: Long = 0L
            override fun init(op: Int, src: String?, dest: String?, max: Long) {
                if (max > 0) total = max
                transferred = 0L
                onProgress(transferred, total)
            }
            override fun count(count: Long): Boolean {
                transferred += count
                onProgress(transferred, total)
                return true
            }
            override fun end() {
                if (total <= 0L) total = transferred
                onProgress(total, total)
            }
        }
        sftp.put(input, remoteName, monitor, ChannelSftp.OVERWRITE)
    }

    override suspend fun deleteRemote(path: String) = withSftp { it.rm(path) }

    private suspend fun withSftp(block: (ChannelSftp) -> Unit) = withContext(Dispatchers.IO) {
        val session = session() ?: error("SSH session is not connected")
        val sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(CHANNEL_CONNECT_TIMEOUT_MS)
        try {
            block(sftp)
        } finally {
            runCatching { sftp.disconnect() }
        }
    }

    private companion object {
        const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000
    }
}
