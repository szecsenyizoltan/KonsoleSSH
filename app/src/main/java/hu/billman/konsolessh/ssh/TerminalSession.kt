package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.model.ConnectionConfig
import java.io.InputStream

/**
 * Egy interaktív shell-munkamenet. A [SshSession] a valós impl; az interfész
 * miatt a munkamenet-kezelés hálózat nélkül is tesztelhető.
 */
interface TerminalSession {
    val isConnected: Boolean

    /** Kapcsolódik (jump-láncon át is); hibánál kivételt dob. */
    suspend fun connect(cols: Int, rows: Int, onProgress: suspend (String) -> Unit)

    /** Blokkoló olvasás a shell-csatornából; -1 a folyam végén. */
    fun read(buffer: ByteArray): Int

    fun send(bytes: ByteArray)

    fun resize(cols: Int, rows: Int)

    suspend fun upload(
        input: InputStream,
        remoteName: String,
        size: Long,
        onProgress: (transferred: Long, total: Long) -> Unit,
    )

    suspend fun deleteRemote(path: String)

    fun disconnect()
}

fun interface TerminalSessionFactory {
    fun create(
        config: ConnectionConfig,
        jumps: List<ConnectionConfig>,
        prompter: SessionPrompter,
    ): TerminalSession
}

/**
 * Felhasználói döntések a kapcsolódás közben. Az IO-szálon, blokkolva hívódik
 * (a JSch így kéri); a válasz a felület felől érkezik.
 */
interface SessionPrompter {
    /** Null: a felhasználó megszakította, vagy lejárt az idő. */
    fun password(displayHost: String): String?

    fun confirmHostKey(prompt: HostKeyPrompt): Boolean

    companion object {
        /** Felület nélkül: jelszót nem ad, ismeretlen kulcsot nem fogad el. */
        val Reject: SessionPrompter = object : SessionPrompter {
            override fun password(displayHost: String): String? = null
            override fun confirmHostKey(prompt: HostKeyPrompt): Boolean = false
        }
    }
}
