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
}
