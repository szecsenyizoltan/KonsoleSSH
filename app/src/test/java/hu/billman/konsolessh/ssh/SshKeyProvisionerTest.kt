package hu.billman.konsolessh.ssh

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Checks the key upload command with a real POSIX shell: special
 * characters in the key's comment (backtick, $, quotes) must not run, and
 * exactly the key lands in the file.
 */
class SshKeyProvisionerTest {

    @Test
    fun `authorized_keys command writes the key verbatim, without shell expansion`() {
        val home = createTempDirectory()
        val marker = File(home, "pwned")
        val key = "ssh-rsa AAAAB3Nza konsolessh:o'neil@`touch ${marker.path}`\$(touch ${marker.path})\"x\""

        val process = ProcessBuilder("sh", "-c", SshKeyProvisioner.authorizedKeysCommand(key))
            .apply { environment()["HOME"] = home.path }
            .redirectErrorStream(true)
            .start()
        assertEquals(0, process.waitFor())

        assertEquals(key + "\n", File(home, ".ssh/authorized_keys").readText())
        assertEquals(false, marker.exists())
    }

    private fun createTempDirectory(): File =
        File.createTempFile("home", null).apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
}
