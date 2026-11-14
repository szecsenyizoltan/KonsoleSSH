package hu.billman.konsolessh.sync

import hu.billman.konsolessh.model.ConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    private fun conn(id: String, name: String, at: Long) = ConnectionConfig(
        id = id,
        name = name,
        host = "h",
        updatedAt = at
    )

    private fun names(p: SyncPayload) = p.connections.associate { it.id to it.name }

    @Test
    fun `connections from both devices end up on both`() {
        val merged = SyncMerge.merge(
            SyncPayload(connections = listOf(conn("a", "A", 1))),
            SyncPayload(connections = listOf(conn("b", "B", 1))),
        )
        assertEquals(mapOf("a" to "A", "b" to "B"), names(merged))
    }

    @Test
    fun `the newer edit of the same connection wins`() {
        val merged = SyncMerge.merge(
            SyncPayload(connections = listOf(conn("a", "old", 1))),
            SyncPayload(connections = listOf(conn("a", "new", 2))),
        )
        assertEquals(mapOf("a" to "new"), names(merged))
    }

    @Test
    fun `a deletion removes the connection on the other device too`() {
        val merged = SyncMerge.merge(
            SyncPayload(connections = listOf(conn("a", "A", 1))),
            SyncPayload(deletedConnections = mapOf("a" to 2L)),
        )
        assertTrue(merged.connections.isEmpty())
        assertEquals(mapOf("a" to 2L), merged.deletedConnections)
    }

    @Test
    fun `an edit after the deletion brings the connection back`() {
        val merged = SyncMerge.merge(
            SyncPayload(connections = listOf(conn("a", "edited", 3))),
            SyncPayload(deletedConnections = mapOf("a" to 2L)),
        )
        assertEquals(mapOf("a" to "edited"), names(merged))
    }

    @Test
    fun `merging is symmetric and repeatable`() {
        val x = SyncPayload(
            connections = listOf(conn("a", "x", 5), conn("b", "B", 1)),
            hostKeys = listOf(SyncedHostKey("h", "ssh-ed25519", "AAA", 1)),
        )
        val y = SyncPayload(
            connections = listOf(conn("a", "y", 5)),
            deletedConnections = mapOf("b" to 1L),
            hostKeys = listOf(SyncedHostKey("h", "ssh-ed25519", "BBB", 1)),
        )
        val xy = SyncMerge.merge(x, y)
        val yx = SyncMerge.merge(y, x)
        assertEquals(xy.connections.toSet(), yx.connections.toSet())
        assertEquals(xy.hostKeys.toSet(), yx.hostKeys.toSet())
        assertEquals(xy.deletedConnections, yx.deletedConnections)
        // Merging again, with itself or with an input, changes nothing.
        val again = SyncMerge.merge(SyncMerge.merge(xy, xy), y)
        assertEquals(xy.connections.toSet(), again.connections.toSet())
        assertEquals(xy.hostKeys.toSet(), again.hostKeys.toSet())
    }

    @Test
    fun `a replaced host key replaces the old one everywhere, a removal removes it`() {
        val merged = SyncMerge.merge(
            SyncPayload(
                hostKeys = listOf(SyncedHostKey("h", "ssh-ed25519", "OLD", 1), SyncedHostKey("g", "ssh-rsa", "G", 1))
            ),
            SyncPayload(
                hostKeys = listOf(SyncedHostKey("h", "ssh-ed25519", "NEW", 2), SyncedHostKey("g", "ssh-rsa", null, 3))
            ),
        )
        assertEquals(
            setOf(SyncedHostKey("h", "ssh-ed25519", "NEW", 2), SyncedHostKey("g", "ssh-rsa", null, 3)),
            merged.hostKeys.toSet(),
        )
    }

    @Test
    fun `the payload survives its own encoding, secrets included`() {
        val payload = SyncPayload(
            connections = listOf(conn("a", "A", 1).copy(password = "pw", privateKey = "KEY")),
            deletedConnections = mapOf("b" to 2L),
            hostKeys = listOf(SyncedHostKey("h", "ssh-ed25519", "AAA", 3)),
        )
        assertEquals(payload, SyncPayload.decode(payload.encode()))
    }
}
