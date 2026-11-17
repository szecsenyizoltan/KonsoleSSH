package hu.billman.konsolessh.ssh

import java.io.InputStream

/**
 * Single-file transfer to the remote home directory. Kept apart from
 * [TerminalSession] so that a session without file transfer, such as a
 * test fake, does not have to implement it.
 */
interface FileTransfer {
    /**
     * Uploads [input] as [remoteName]. [size] scales the progress when the
     * server does not report the length; negative means unknown.
     */
    suspend fun upload(
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    )

    /** Deletes a remote file; the path is relative to the remote home directory. */
    suspend fun deleteRemote(path: String)
}
