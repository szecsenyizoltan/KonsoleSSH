package hu.billman.konsolessh.sync

import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.serialization.json.Json

/**
 * Merges two devices' states, item by item: per connection and per host
 * key (host and type) the newer change wins, and a deletion counts as a
 * change at the time it happened. Merging is symmetric and repeatable, so
 * every device ends with the same result whatever order they sync in.
 */
object SyncMerge {

    fun merge(a: SyncPayload, b: SyncPayload): SyncPayload {
        val deleted = (a.deletedConnections.keys + b.deletedConnections.keys).associateWith { id ->
            maxOf(a.deletedConnections[id] ?: 0L, b.deletedConnections[id] ?: 0L)
        }
        val byIdA = a.connections.associateBy { it.id }
        val byIdB = b.connections.associateBy { it.id }
        val connections = (byIdA.keys + byIdB.keys).mapNotNull { id ->
            val newest = newer(byIdA[id], byIdB[id])
            // A deletion at the same moment as an edit wins: losing a connection
            // the user deleted is less surprising than seeing it come back.
            newest?.takeIf { it.updatedAt > (deleted[id] ?: Long.MIN_VALUE) }
        }
        val hostKeys = (a.hostKeys + b.hostKeys)
            .groupBy { it.host to it.type }
            .values
            .map { versions -> versions.reduce(::newerKey) }
        return SyncPayload(connections, deleted, hostKeys)
    }

    private fun newer(x: ConnectionConfig?, y: ConnectionConfig?): ConnectionConfig? = when {
        x == null -> y
        y == null -> x
        y.updatedAt > x.updatedAt -> y
        // Same time on two devices: a choice by content keeps every device in
        // agreement. hashCode would not do: an enum's differs between processes.
        y.updatedAt == x.updatedAt && canonical(y) > canonical(x) -> y
        else -> x
    }

    private val json = Json { encodeDefaults = true }

    private fun canonical(c: ConnectionConfig): String = json.encodeToString(ConnectionConfig.serializer(), c)

    private fun newerKey(x: SyncedHostKey, y: SyncedHostKey): SyncedHostKey = when {
        y.at > x.at -> y
        y.at == x.at && (y.key ?: "") > (x.key ?: "") -> y
        else -> x
    }
}
