package hu.billman.konsolessh.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicFileWriteTest {

    private val dir = Files.createTempDirectory("atomic-write").toFile().apply { deleteOnExit() }

    @Test
    fun `creates the file and its missing parent directories`() {
        val target = File(dir, "sub/dir/file")
        target.writeAtomically(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test
    fun `replaces the old content and leaves no temporary file`() {
        val target = File(dir, "file").apply { writeText("old content that is longer") }
        target.writeAtomically("new".toByteArray())
        assertEquals("new", target.readText())
        assertFalse(File(dir, "file.tmp").exists())
    }

    @Test
    fun `a failed rename throws, keeps the target and removes the temporary file`() {
        // A non-empty directory cannot be replaced by a file, so the move fails.
        val target = File(dir, "occupied").apply { mkdirs() }
        File(target, "inside").writeText("kept")

        val failed = runCatching { target.writeAtomically("new".toByteArray()) }

        assertTrue(failed.isFailure)
        assertEquals("kept", File(target, "inside").readText())
        assertFalse(File(dir, "occupied.tmp").exists())
    }
}
