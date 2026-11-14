package hu.billman.konsolessh.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

/** The known_hosts store with a corrupt file, parallel writes and reloading. */
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

    @Test
    fun `acceptances and removals keep their times for sync, in a separate file`() {
        var now = 10L
        val store = HostKeyStore(file) { now }
        store.put("a", "ssh-ed25519", "AAAA")
        now = 20L
        store.put("b", "ssh-rsa", "BBBB")
        now = 30L
        store.remove("b", null, null)

        assertEquals(
            setOf(HostKeyStore.Change("a", "ssh-ed25519", "AAAA", 10L), HostKeyStore.Change("b", "ssh-rsa", null, 30L)),
            HostKeyStore(file).changes().toSet(),
        )
        assertEquals("a ssh-ed25519 AAAA\n", file.readText())
    }

    @Test
    fun `keys from before change times count as time zero`() {
        file.writeText("a ssh-ed25519 AAAA\n")
        assertEquals(listOf(HostKeyStore.Change("a", "ssh-ed25519", "AAAA", 0L)), HostKeyStore(file).changes())
    }

    @Test
    fun `a merged state from sync replaces keys and times`() {
        val merged = listOf(
            HostKeyStore.Change("x", "ssh-ed25519", "XXXX", 5L),
            HostKeyStore.Change("y", "ssh-rsa", null, 6L),
        )
        HostKeyStore(file).replaceFromSync(merged)
        val reloaded = HostKeyStore(file)
        assertEquals(listOf(HostKeyStore.Entry("x", "ssh-ed25519", "XXXX")), reloaded.entries())
        assertEquals(merged.toSet(), reloaded.changes().toSet())
    }
}
