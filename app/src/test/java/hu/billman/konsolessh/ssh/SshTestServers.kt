package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Valódi OpenSSH szerverek a tesztekhez, a gépen futó `sshd`-vel: egy átjáró
 * (gw), egy belső gép (hv) és a cél (vm), mind 127.0.0.1-en, szabad porton.
 * A gw és a hv csak továbbít; a vm kapcsolódáskor egy szkriptet futtat, amely
 * jelzőt ír, a PID-jét fájlba menti, majd a bemenetet visszhangozza.
 *
 * Ha nincs `sshd` (fejlesztői gép csomag nélkül), a tesztek kihagyódnak; a
 * CI telepíti, és ott a hiánya hiba (KONSOLE_REQUIRE_SSHD=1).
 */
class SshTestServers : AutoCloseable {

    val dir: File = Files.createTempDirectory("konsole-sshd").toFile()
    val uploadDir = File(dir, "upload").apply { mkdirs() }
    val shellPidFile = File(dir, "shell.pid")
    private val user: String = System.getProperty("user.name")
    private val clientKey = File(dir, "client_key")
    private val processes = mutableMapOf<String, Process>()
    private val ports = mutableMapOf<String, Int>()

    init {
        // CI sets KONSOLE_REQUIRE_SSHD, so there a missing sshd fails instead of skipping.
        if (System.getenv("KONSOLE_REQUIRE_SSHD") == "1") check(SSHD.canExecute()) { "sshd is required" }
        assumeTrue("sshd not installed", SSHD.canExecute())
        keygen(clientKey, "rsa", "-b", "2048", "-m", "PEM")
        File(dir, "authorized_keys").writeText(File(clientKey.path + ".pub").readText())
        File(dir, "vm_cmd.sh").apply {
            writeText(
                """
                #!/bin/sh
                case "${'$'}SSH_ORIGINAL_COMMAND" in
                  *sftp*) exec $SFTP_SERVER -d ${uploadDir.path} ;;
                  *) echo $MARKER; echo ${'$'}${'$'} > ${shellPidFile.path}; exec cat ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        listOf("gw", "hv", "vm").forEach { start(it) }
    }

    fun port(name: String) = ports.getValue(name)

    fun config(name: String, jump: ConnectionConfig? = null) = ConnectionConfig(
        id = name,
        name = name,
        host = LOOPBACK,
        port = port(name),
        username = user,
        authType = ConnectionConfig.AuthType.PRIVATE_KEY,
        privateKey = clientKey.readText(),
        jumpConnectionId = jump?.id.orEmpty(),
    )

    /** A szerver új host kulccsal indul újra (pl. megváltozott kulcs teszteléséhez). */
    fun restartWithNewHostKey(name: String) {
        processes.remove(name)?.let { process ->
            process.destroy()
            process.waitFor(STOP_SECONDS, TimeUnit.SECONDS)
        }
        File(dir, "hk_$name").delete()
        File(dir, "hk_$name.pub").delete()
        start(name, ports.getValue(name))
    }

    /** Élő TCP-kapcsolatok száma a szerver portjára (a /proc/net/tcp alapján). */
    fun established(name: String): Int {
        val hexPort = "%04X".format(port(name))
        return listOf("/proc/net/tcp", "/proc/net/tcp6").map(::File).filter { it.exists() }.sumOf { file ->
            file.readLines().drop(1).count { line ->
                val cols = line.trim().split(Regex("\\s+"))
                cols[1].endsWith(":$hexPort") && cols[3] == TCP_ESTABLISHED
            }
        }
    }

    /**
     * What is left of the servers, for a failure message: the shell, every
     * process under each sshd with its state, every TCP socket on the server
     * ports in any state, and the server logs. CI failures that do not
     * reproduce locally are diagnosed from this.
     */
    fun diagnostics(): String = buildString {
        val shell = shellPidFile.takeIf { it.exists() }?.readText()?.trim()
        appendLine("shell pid: ${shell ?: "-"} ${shell?.let(::describe).orEmpty()}")
        val all = processTable()
        processes.keys.forEach { name ->
            val root = File(dir, "$name.pid").takeIf { it.exists() }?.readText()?.trim()
            appendLine("sshd $name (pid ${root ?: "?"}, port ${ports[name]}):")
            root?.let { descendants(it, all) }?.forEach { pid -> appendLine("  ${describe(pid)}") }
        }
        val hexPorts = ports.values.map { "%04X".format(it) }.toSet()
        appendLine("tcp sockets on server ports:")
        listOf("/proc/net/tcp", "/proc/net/tcp6").map(::File).filter { it.exists() }.forEach { file ->
            file.readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
                .filter { cols -> cols[1].substringAfter(':') in hexPorts || cols[2].substringAfter(':') in hexPorts }
                .forEach { cols -> appendLine("  ${cols[1]} -> ${cols[2]} state ${TCP_STATES[cols[3]] ?: cols[3]}") }
        }
        dir.listFiles().orEmpty().filter { it.name.endsWith(".log") }.forEach { log ->
            appendLine("${log.name}: ${log.readText().trim().takeLast(LOG_TAIL).ifEmpty { "(empty)" }}")
        }
    }

    /** pid -> parent pid, for every process visible in /proc. */
    private fun processTable(): Map<String, String> =
        File("/proc").listFiles().orEmpty().filter { it.name.all(Char::isDigit) }.mapNotNull { proc ->
            val stat = runCatching { File(proc, "stat").readText() }.getOrNull() ?: return@mapNotNull null
            proc.name to stat.substringAfterLast(')').trim().split(' ').getOrElse(1) { "" }
        }.toMap()

    private fun descendants(root: String, table: Map<String, String>): List<String> {
        val children = table.entries.groupBy({ it.value }, { it.key })
        val result = mutableListOf<String>()
        val queue = ArrayDeque(children[root].orEmpty())
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            result += pid
            queue.addAll(children[pid].orEmpty())
        }
        return result
    }

    private fun describe(pid: String): String {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return "(gone)"
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val cmdline = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ').trim() }
            .getOrDefault("")
        return "pid $pid state ${fields.getOrElse(0) { "?" }} ppid ${fields.getOrElse(1) { "?" }} " +
            "${stat.substringAfter('(').substringBeforeLast(')')} [$cmdline]"
    }

    /**
     * Whether the remote shell still runs. A process that has exited but was
     * not reaped stays in /proc as a zombie; in a CI container whose init is
     * a plain `sleep`, orphaned shells are never reaped, so the state field of
     * /proc/<pid>/stat decides, not the directory.
     */
    fun shellAlive(): Boolean {
        val pid = shellPidFile.takeIf { it.exists() }?.readText()?.trim() ?: return false
        return processRunning(pid)
    }

    private fun start(name: String, fixedPort: Int? = null) {
        val hostKey = File(dir, "hk_$name")
        if (!hostKey.exists()) keygen(hostKey, "ed25519")
        val port = fixedPort ?: ServerSocket(0).use { it.localPort }
        ports[name] = port
        val forceCommand = if (name == "vm") File(dir, "vm_cmd.sh").path else "echo jump-only-$name"
        val config = File(dir, "sshd_$name").apply {
            writeText(
                """
                Port $port
                ListenAddress $LOOPBACK
                HostKey ${hostKey.path}
                PidFile ${File(dir, "$name.pid").path}
                UsePAM no
                StrictModes no
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                AuthorizedKeysFile ${File(dir, "authorized_keys").path}
                AllowTcpForwarding yes
                ForceCommand $forceCommand
                Subsystem sftp $SFTP_SERVER
                LogLevel ERROR
                """.trimIndent() + "\n",
            )
        }
        processes[name] = ProcessBuilder(SSHD.path, "-D", "-e", "-f", config.path)
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "$name.log"))
            .start()
        awaitListening(port)
    }

    private fun awaitListening(port: Int) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val up = runCatching { Socket(LOOPBACK, port).close() }.isSuccess
            if (up) return
            Thread.sleep(POLL_MS)
        }
        val logs = dir.listFiles().orEmpty().filter { it.name.endsWith(".log") }.joinToString { it.readText() }
        error("sshd did not start on $port: $logs")
    }

    private fun keygen(file: File, type: String, vararg extra: String) {
        val p = ProcessBuilder("ssh-keygen", "-q", "-t", type, *extra, "-N", "", "-f", file.path)
            .redirectErrorStream(true).start()
        check(p.waitFor(START_TIMEOUT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0) { "ssh-keygen failed" }
    }

    override fun close() {
        processes.values.forEach { it.destroy() }
        processes.values.forEach { it.waitFor(STOP_SECONDS, TimeUnit.SECONDS) }
        dir.deleteRecursively()
    }

    companion object {
        /** Zombie and dead, from proc(5). */
        private val DEAD_STATES = setOf('Z', 'X', 'x')

        /** True while [pid] runs; a zombie counts as gone. */
        fun processRunning(pid: String): Boolean {
            val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
            // "pid (comm) S ...": comm may contain spaces and parentheses, the state follows the last ')'.
            val state = stat.substringAfterLast(')').trim().firstOrNull()
            return state != null && state !in DEAD_STATES
        }

        const val MARKER = "KONSOLE_TEST_SHELL"
        const val LOOPBACK = "127.0.0.1"
        private val SSHD = File("/usr/sbin/sshd")
        private val SFTP_SERVER = listOf("/usr/lib/openssh/sftp-server", "/usr/libexec/openssh/sftp-server")
            .firstOrNull { File(it).exists() } ?: "/usr/lib/openssh/sftp-server"
        private const val TCP_ESTABLISHED = "01"
        private const val LOG_TAIL = 2000
        private val TCP_STATES = mapOf(
            "01" to "ESTABLISHED", "02" to "SYN_SENT", "03" to "SYN_RECV", "04" to "FIN_WAIT1",
            "05" to "FIN_WAIT2", "06" to "TIME_WAIT", "07" to "CLOSE", "08" to "CLOSE_WAIT",
            "09" to "LAST_ACK", "0A" to "LISTEN", "0B" to "CLOSING",
        )
        private const val START_TIMEOUT_MS = 10_000L
        private const val STOP_SECONDS = 5L
        private const val POLL_MS = 50L
    }
}
