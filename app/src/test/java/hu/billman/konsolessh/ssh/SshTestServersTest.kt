package hu.billman.konsolessh.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** The test servers' process check, which the CI container depends on. */
class SshTestServersTest {

    @Test
    fun `a running process is running, an exited but unreaped one is not`() {
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        // sh starts a child that exits at once, then becomes `sleep`, which never
        // reaps it: the child stays in /proc as a zombie, as in a container whose
        // init does not reap orphans.
        val parent = ProcessBuilder("sh", "-c", "sleep 0 & echo \$!; echo \$\$; exec sleep 5").start()
        // `exec` keeps the shell's pid, so $$ is the pid of the sleep.
        val output = parent.inputStream.bufferedReader()
        val zombie = output.readLine().trim()
        val sleeper = output.readLine().trim()
        try {
            val deadline = System.currentTimeMillis() + 2_000
            while (SshTestServers.processRunning(zombie) && System.currentTimeMillis() < deadline) Thread.sleep(20)

            assertTrue(File("/proc/$zombie").exists())
            assertFalse(SshTestServers.processRunning(zombie))
            assertTrue(SshTestServers.processRunning(sleeper))
        } finally {
            parent.destroy()
            parent.waitFor(5, TimeUnit.SECONDS)
        }
        assertFalse(SshTestServers.processRunning(sleeper))
    }

    @Test
    fun `an empty or malformed pid is no process`() {
        // A shell killed between creating its pid file and writing it leaves the file
        // empty; "/proc//stat" would be /proc/stat, which always exists.
        assertFalse(SshTestServers.processRunning(""))
        assertFalse(SshTestServers.processRunning("self"))
        assertFalse(SshTestServers.processRunning("12 "))
    }

    @Test
    fun `diagnostics list every server, its processes and its listening socket`() {
        SshTestServers().use { servers ->
            val text = servers.diagnostics()
            println(text)
            listOf("gw", "hv", "vm").forEach { name ->
                assertTrue(text, text.contains("sshd $name (pid "))
                assertTrue(text, text.contains("%04X".format(servers.port(name))))
            }
            assertTrue(text, text.contains("LISTEN"))
        }
    }
}
