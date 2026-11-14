package hu.billman.konsolessh.sync

import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What one device knows, and what the encrypted sync file holds: the saved
 * connections with their change times, when deleted connections were
 * deleted, and the accepted host keys with their removals.
 */
@Serializable
data class SyncPayload(
    val connections: List<ConnectionConfig> = emptyList(),
    val deletedConnections: Map<String, Long> = emptyMap(),
    val hostKeys: List<SyncedHostKey> = emptyList(),
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        // Unknown fields are skipped, so a newer app's file still merges here.
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        fun decode(bytes: ByteArray): SyncPayload = json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}

/** A host key with its last change; a null [key] is a removal. */
@Serializable
data class SyncedHostKey(val host: String, val type: String, val key: String? = null, val at: Long = 0L)
