package hu.billman.konsolessh.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import hu.billman.konsolessh.model.ConnectionConfig
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The connection store's file format: the [ConnectionJson] list, encrypted
 * as one block by the [CryptoBox]. An empty file is an empty list.
 */
internal class EncryptedConnectionsSerializer(private val cryptoBox: CryptoBox) : Serializer<List<ConnectionConfig>> {

    override val defaultValue: List<ConnectionConfig> = emptyList()

    override suspend fun readFrom(input: InputStream): List<ConnectionConfig> {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        // Keystore and parse failures surface as many unrelated types; all mean
        // the same here: the store cannot be read, and must not be overwritten.
        @Suppress("TooGenericExceptionCaught")
        return try {
            ConnectionJson.decode(String(cryptoBox.decrypt(bytes), Charsets.UTF_8))
        } catch (e: Exception) {
            throw CorruptionException("stored connections are unreadable", e)
        }
    }

    override suspend fun writeTo(t: List<ConnectionConfig>, output: OutputStream) {
        @Suppress("TooGenericExceptionCaught")
        val encrypted = try {
            cryptoBox.encrypt(ConnectionJson.encode(t).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Nothing may reach the file unencrypted.
            throw IOException("encryption failed, nothing was written", e)
        }
        output.write(encrypted)
    }
}
