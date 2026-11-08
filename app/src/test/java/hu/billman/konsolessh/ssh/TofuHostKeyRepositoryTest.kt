package hu.billman.konsolessh.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Pure JVM teszt: a TOFU-döntések és a tár perzisztenciája, hálózat nélkül. */
class TofuHostKeyRepositoryTest {

    private lateinit var file: File
    private lateinit var store: HostKeyStore

    @Before
    fun setUp() {
        file = File.createTempFile("known_hosts", null).apply {
            delete()
            deleteOnExit()
        }
        store = HostKeyStore(file)
    }

    private fun blob(type: String, fill: Int, size: Int = 32): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(type.length)
            it.writeBytes(type)
            it.writeInt(size)
            it.write(ByteArray(size) { fill.toByte() })
        }
        return out.toByteArray()
    }

    private fun accept(repo: TofuHostKeyRepository, host: String, key: ByteArray) =
        repo.add(HostKey(host, key), null)

    @Test
    fun `unknown host is NOT_INCLUDED and asks as new`() {
        val repo = TofuHostKeyRepository(store)
        val key = blob("ssh-ed25519", 1)

        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("srv", key))
        val prompt = repo.pending!!
        assertEquals("srv", prompt.host)
        assertEquals("ssh-ed25519", prompt.keyType)
        assertFalse(prompt.changed)
    }

    @Test
    fun `accepted key is OK on a later connection and survives reload`() {
        val key = blob("ssh-ed25519", 1)
        accept(TofuHostKeyRepository(store), "srv", key)

        val fresh = TofuHostKeyRepository(HostKeyStore(file))
        assertEquals(HostKeyRepository.OK, fresh.check("srv", key))
        assertNull(fresh.pending)
    }

    @Test
    fun `different key of the same type is CHANGED`() {
        accept(TofuHostKeyRepository(store), "srv", blob("ssh-ed25519", 1))

        val repo = TofuHostKeyRepository(store)
        assertEquals(HostKeyRepository.CHANGED, repo.check("srv", blob("ssh-ed25519", 2)))
        assertTrue(repo.pending!!.changed)
    }

    @Test
    fun `replacing a changed key keeps only the new one`() {
        val repo = TofuHostKeyRepository(store)
        accept(repo, "srv", blob("ssh-ed25519", 1))
        val newKey = blob("ssh-ed25519", 2)

        repo.remove("srv", "ssh-ed25519", null)
        accept(repo, "srv", newKey)

        assertEquals(1, store.entries().size)
        assertEquals(HostKeyRepository.OK, repo.check("srv", newKey))
    }

    @Test
    fun `rsa signature algorithm name removes the ssh-rsa key`() {
        val repo = TofuHostKeyRepository(store)
        accept(repo, "srv", blob("ssh-rsa", 1))

        repo.remove("srv", "rsa-sha2-512", null)

        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `key stored for one port does not match another port`() {
        val key = blob("ssh-ed25519", 1)
        accept(TofuHostKeyRepository(store), TofuHostKeyRepository.alias("srv", 2222), key)

        val repo = TofuHostKeyRepository(store)
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("srv", key))
        assertEquals(HostKeyRepository.OK, repo.check("[srv]:2222", key))
    }

    @Test
    fun `getHostKey returns stored keys for host and rsa algorithm`() {
        val key = blob("ssh-rsa", 3)
        accept(TofuHostKeyRepository(store), "srv", key)

        val found = TofuHostKeyRepository(store).getHostKey("srv", "rsa-sha2-256")
        assertEquals(1, found.size)
        assertEquals("ssh-rsa", found[0].type)
    }

    @Test
    fun `alias omits the default port`() {
        assertEquals("srv", TofuHostKeyRepository.alias("srv", 22))
        assertEquals("[srv]:2222", TofuHostKeyRepository.alias("srv", 2222))
    }

    @Test
    fun `fingerprint uses OpenSSH SHA256 format`() {
        val fp = TofuHostKeyRepository.fingerprint(blob("ssh-ed25519", 1))
        assertTrue(fp.startsWith("SHA256:"))
        assertFalse(fp.endsWith("="))
        assertEquals("SHA256:".length + 43, fp.length)
    }

    @Test
    fun `keyType reads the algorithm name and rejects garbage`() {
        assertEquals("ecdsa-sha2-nistp256", TofuHostKeyRepository.keyType(blob("ecdsa-sha2-nistp256", 0)))
        assertEquals("unknown", TofuHostKeyRepository.keyType(byteArrayOf(0, 0, 0)))
        assertEquals("unknown", TofuHostKeyRepository.keyType(byteArrayOf(0, 0, 0, 99, 1)))
    }

    @Test
    fun `store ignores malformed lines`() {
        val key = blob("ssh-ed25519", 1)
        accept(TofuHostKeyRepository(store), "srv", key)
        file.appendText("broken line\n\n")

        val entries = HostKeyStore(file).entries()
        assertEquals(1, entries.size)
        assertArrayEquals(key, java.util.Base64.getDecoder().decode(entries[0].key))
    }
}
