package hu.billman.konsolessh.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

/** A known_hosts tár sérült fájllal, párhuzamos írással és újratöltéssel. */
class HostKeyStoreTest {

    private val dir = Files.createTempDirectory("known-hosts").toFile().apply { deleteOnExit() }
    private val file = File(dir, "known_hosts")

    @Test
    fun `damaged lines are skipped and the valid entries survive a write`() {
        file.writeText(
            "[a.example]:2222 ssh-ed25519 AAAAvalid1\n" +
                "garbage without fields\n" +
                "\n" +
                "b.example ssh-rsa AAAAvalid2\r\n" +
                "c.example ssh-ed25519 AAAA too many fields\n" +
                "d.example ssh-ed25519",
        )
        val store = HostKeyStore(file)
        assertEquals(listOf("[a.example]:2222", "b.example"), store.entries().map { it.host })

        store.put("e.example", "ssh-ed25519", "AAAAnew")

        val reloaded = HostKeyStore(file).entries().map { it.host }
        assertEquals(listOf("[a.example]:2222", "b.example", "e.example"), reloaded)
        assertFalse("no temp file left", File(file.path + ".tmp").exists())
    }

    @Test
    fun `a missing or empty file is an empty store`() {
        assertTrue(HostKeyStore(file).entries().isEmpty())
        file.writeText("")
        assertTrue(HostKeyStore(file).entries().isEmpty())
    }

    @Test
    fun `parallel writers from many tabs lose no entry`() {
        val store = HostKeyStore(file)
        val writers = List(WRITERS) { w ->
            thread { repeat(PER_WRITER) { i -> store.put("host-$w-$i", "ssh-ed25519", "AAAA$w$i") } }
        }
        writers.forEach { it.join() }
        assertEquals(WRITERS * PER_WRITER, HostKeyStore(file).entries().size)
    }

    private companion object {
        const val WRITERS = 8
        const val PER_WRITER = 25
    }
}
