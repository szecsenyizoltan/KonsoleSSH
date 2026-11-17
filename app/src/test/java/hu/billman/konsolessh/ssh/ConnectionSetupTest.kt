package hu.billman.konsolessh.ssh

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSetupTest {

    private val target = ConnectionConfig(id = "t", host = "203.0.113.5", username = "root")

    @Test
    fun `the key is generated off the calling thread`() = runBlocking {
        val provisioner = mockk<SshKeyProvisioner>()
        coEvery { provisioner.uploadPublicKey(any(), any(), any(), any()) } returns SshKeyProvisioner.ServerType.OPENSSH
        var generatedOn: Thread? = null
        val keys = SshKeyFactory { _, comment ->
            generatedOn = Thread.currentThread()
            GeneratedKey(privatePem = "PEM", publicLine = "ssh-ed25519 AAAA $comment")
        }
        val setup = ConnectionSetup(FakeConnectionRepository(listOf(target)), provisioner, keys)

        val result = setup.installNewKey(target, "SSH-2.0-OpenSSH_9.6", SessionPrompter.Reject)

        assertTrue(result is ConnectionSetup.KeyResult.Installed)
        assertNotSame(Thread.currentThread(), generatedOn)
    }
}
