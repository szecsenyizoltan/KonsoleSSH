package hu.billman.konsolessh.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * When each deleted connection was deleted (id to epoch milliseconds). It is
 * a file of its own, next to the connection store, so that the store's
 * format stays the one 2.0 and 2.1 read. It holds ids and times only, no
 * secrets, and is not encrypted.
 */
@Serializable
internal data class DeletedConnections(val deleted: Map<String, Long> = emptyMap())

internal object DeletedConnectionsSerializer : Serializer<DeletedConnections> {

    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = DeletedConnections()

    override suspend fun readFrom(input: InputStream): DeletedConnections {
        val text = input.readBytes().toString(Charsets.UTF_8)
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString(DeletedConnections.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("deleted connections are unreadable", e)
        }
    }

    override suspend fun writeTo(t: DeletedConnections, output: OutputStream) {
        output.write(json.encodeToString(DeletedConnections.serializer(), t).toByteArray(Charsets.UTF_8))
    }
}
