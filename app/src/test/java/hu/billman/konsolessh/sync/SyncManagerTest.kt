package hu.billman.konsolessh.sync

import hu.billman.konsolessh.data.FakeConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import hu.billman.konsolessh.ssh.HostKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Two devices syncing through one shared, in-memory Drive file. */
class SyncManagerTest {

    private class RecordingScheduler : SyncScheduler {
        var pending = false
        override fun schedule() {
            pending = true
        }

        override fun cancel() {
            pending = false
        }
    }

    private inner class Device(delayMs: Long = 3_000L) {
        val repo = FakeConnectionRepository(clock = { now })
        val hostKeys = HostKeyStore(File(Files.createTempDirectory("sync").toFile(), "known_hosts"), clock = { now })
        val keys = MemoryKeys()
        val scheduler = RecordingScheduler()
        val sync = SyncManager(
            connections = repo,
            hostKeys = hostKeys,
            keys = keys,
            remote = drive,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            scheduler = scheduler,
            timing = SyncManager.Timing(clock = { now }, iterations = 1_000, delayMs = delayMs),
        )

        suspend fun names() = repo.load().map { it.name }
    }

    private val drive = MemoryDrive()
    private var now = 100L

    private fun conn(id: String, name: String, at: Long) = ConnectionConfig(
        id = id,
        name = name,
        host = "h",
        auth = ConnectionConfig.Auth.Password("secret-$id"),
        updatedAt = at,
    )

    @Test
    fun `the first device creates the file, the second joins and gets everything`() = runBlocking {
        val first = Device()
        first.repo.saveOne(conn("a", "Alpha", 1))
        first.hostKeys.put("a.example", "ssh-ed25519", "AAAA")
        assertFalse(first.sync.backupExists())
        assertEquals(SyncManager.Enabled.CREATED, first.sync.enable("pass".toCharArray()))
        assertFalse("no plaintext in Drive", drive.content!!.contains("secret-a"))

        val second = Device()
        assertTrue(second.sync.backupExists())
        assertEquals(SyncManager.Enabled.JOINED, second.sync.enable("pass".toCharArray()))
        assertEquals(listOf("Alpha"), second.names())
        assertEquals(ConnectionConfig.Auth.Password("secret-a"), second.repo.get("a")?.auth)
        assertEquals(listOf("a.example"), second.hostKeys.entries().map { it.host })
    }

    @Test
    fun `a device with its own connections joins without losing any, on either side`() = runBlocking {
        val first = Device()
        first.repo.saveOne(conn("a", "Alpha", 1))
        first.sync.enable("pass".toCharArray())

        // An older device: more connections than Drive, saved before change times existed.
        val old = Device()
        old.repo.saveOne(conn("x", "Xray", 0))
        old.repo.saveOne(conn("y", "Yankee", 0))
        old.sync.enable("pass".toCharArray())
        first.sync.syncNow()

        assertEquals(setOf("Alpha", "Xray", "Yankee"), old.names().toSet())
        assertEquals(setOf("Alpha", "Xray", "Yankee"), first.names().toSet())
    }

    @Test
    fun `edits and deletions travel both ways`() = runBlocking {
        val first = Device()
        first.repo.saveOne(conn("a", "Alpha", 1))
        first.repo.saveOne(conn("b", "Bravo", 1))
        first.sync.enable("pass".toCharArray())
        val second = Device()
        second.sync.enable("pass".toCharArray())

        second.repo.saveOne(conn("a", "Alpha 2", 5))
        now = 6L
        second.repo.delete("b")
        second.sync.syncNow()
        first.sync.syncNow()

        assertEquals(listOf("Alpha 2"), first.names())
        assertEquals(mapOf("b" to 6L), first.repo.syncState().deleted)
    }

    @Test
    fun `a wrong passphrase does not join and leaves sync off`() = runBlocking {
        Device().sync.enable("right".toCharArray())
        val second = Device()
        try {
            second.sync.enable("wrong".toCharArray())
            fail("expected WrongPassphraseException")
        } catch (_: SyncCipher.WrongPassphraseException) {
            // expected
        }
        assertNull(second.keys.key)
        assertEquals(SyncManager.Status.Off, second.sync.status.value)
    }

    @Test
    fun `a waiting sync runs at once when flushed, and an immediate request does not wait`() = runBlocking {
        val device = Device(delayMs = 60_000L)
        device.sync.enable("pass".toCharArray())
        val writes = drive.writes

        device.repo.saveOne(conn("a", "Alpha", 1))
        device.sync.requestSync()
        assertEquals("still waiting", writes, drive.writes)
        device.sync.flush()
        assertEquals("flushed", writes + 1, drive.writes)

        device.repo.saveOne(conn("b", "Bravo", 1))
        device.sync.requestSync(now = true)
        assertEquals("no wait", writes + 2, drive.writes)
    }

    @Test
    fun `nothing changed means nothing is uploaded`() = runBlocking {
        val device = Device()
        device.repo.saveOne(conn("a", "Alpha", 1))
        device.sync.enable("pass".toCharArray())
        val writes = drive.writes
        device.sync.syncNow()
        assertEquals(writes, drive.writes)
        assertTrue(device.sync.status.value is SyncManager.Status.Ready)
    }

    @Test
    fun `turning sync off keeps the local data and stops syncing`() = runBlocking {
        val device = Device()
        device.repo.saveOne(conn("a", "Alpha", 1))
        device.sync.enable("pass".toCharArray())
        device.sync.disable()
        assertEquals(SyncManager.Status.Off, device.sync.status.value)
        assertNull(device.keys.key)
        assertEquals(listOf("Alpha"), device.names())
        val writes = drive.writes
        device.sync.syncNow()
        assertEquals(writes, drive.writes)
    }

    @Test
    fun `a failing Drive is reported in the status`() = runBlocking {
        val device = Device()
        device.sync.enable("pass".toCharArray())
        val broken = SyncManager(
            device.repo,
            device.hostKeys,
            device.keys,
            object : SyncFileStore {
                override suspend fun read(): String = throw java.io.IOException("offline")

                override suspend fun write(content: String) = Unit
            },
            CoroutineScope(Dispatchers.Unconfined),
            timing = SyncManager.Timing(iterations = 1_000),
        )
        runCatching { broken.syncNow() }
        assertTrue((broken.status.value as SyncManager.Status.Failed).cause is java.io.IOException)
    }

    @Test
    fun `a local change keeps a later upload pending until the upload succeeds`() = runBlocking {
        val device = Device(delayMs = 60_000L)
        device.sync.enable("pass".toCharArray())

        device.repo.saveOne(conn("a", "Alpha", 1))
        device.sync.requestSync()
        assertTrue("pending while waiting", device.scheduler.pending)

        device.sync.flush()
        assertFalse("withdrawn after the upload", device.scheduler.pending)
    }

    @Test
    fun `a failed upload leaves the later one pending`() = runBlocking {
        val device = Device()
        device.sync.enable("pass".toCharArray())
        drive.failure = java.io.IOException("offline")

        device.sync.requestSync()
        device.sync.flush()

        assertTrue(device.scheduler.pending)
        assertTrue(device.sync.status.value is SyncManager.Status.Failed)
    }

    @Test
    fun `a sync without a local change schedules nothing, and turning sync off withdraws a pending one`() =
        runBlocking {
            val device = Device()
            device.sync.enable("pass".toCharArray())

            device.sync.requestSync(now = true)
            assertFalse(device.scheduler.pending)

            device.scheduler.schedule()
            device.sync.disable()
            assertFalse(device.scheduler.pending)
        }
}
