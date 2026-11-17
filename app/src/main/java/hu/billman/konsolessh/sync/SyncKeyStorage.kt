package hu.billman.konsolessh.sync

import hu.billman.konsolessh.data.CryptoBox
import hu.billman.konsolessh.data.writeAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * This device's sync key, derived once from the passphrase: with it, the
 * passphrase never has to be stored or typed again on this device.
 */
data class SyncKey(val secret: ByteArray, val params: SyncCipher.KeyParams, val lastSyncAt: Long? = null) {
    override fun equals(other: Any?) = other is SyncKey && secret.contentEquals(other.secret) &&
        params == other.params && lastSyncAt == other.lastSyncAt

    override fun hashCode() = (secret.contentHashCode() * 31 + params.hashCode()) * 31 + lastSyncAt.hashCode()
}

interface SyncKeyStorage {
    suspend fun load(): SyncKey?

    /** Null turns sync off on this device. */
    suspend fun save(key: SyncKey?)
}

/** The sync key in a file, encrypted with the Keystore-backed [CryptoBox] like the connections. */
class CryptoBoxSyncKeyStorage(
    private val file: File,
    private val cryptoBox: CryptoBox,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SyncKeyStorage {

    @Serializable
    private data class Stored(val secret: String, val salt: String, val iterations: Int, val lastSyncAt: Long? = null)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): SyncKey? = withContext(io) {
        if (!file.exists()) return@withContext null
        val text = cryptoBox.decrypt(file.readBytes()).toString(Charsets.UTF_8)
        val stored = json.decodeFromString(Stored.serializer(), text)
        SyncKey(decode(stored.secret), SyncCipher.KeyParams(decode(stored.salt), stored.iterations), stored.lastSyncAt)
    }

    override suspend fun save(key: SyncKey?) = withContext(io) {
        if (key == null) {
            file.delete()
            return@withContext
        }
        val stored = Stored(encode(key.secret), encode(key.params.salt), key.params.iterations, key.lastSyncAt)
        val bytes = cryptoBox.encrypt(json.encodeToString(Stored.serializer(), stored).toByteArray(Charsets.UTF_8))
        file.writeAtomically(bytes)
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String) = Base64.getDecoder().decode(text)
}
