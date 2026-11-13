package hu.billman.konsolessh.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import hu.billman.konsolessh.model.ConnectionConfig
import java.io.File

/**
 * Moves the connections of older versions into the DataStore file, once.
 *
 * Sources, newest first:
 * 1. 1.1.12–1.1.30: the CryptoBox payload in SharedPreferences
 *    ([PREFS_NAME]); before 1.1.27 it could also be a plaintext fallback.
 * 2. Before 1.1.12: security-crypto EncryptedSharedPreferences.
 * 3. The earliest versions: plain SharedPreferences.
 *
 * DataStore calls [cleanUp], which deletes the old files, only after the
 * migrated list is on disk; if writing fails, the old stores stay for the
 * next attempt. An unreadable 1.1.12+ payload fails the migration the same
 * way an unreadable store fails a read, and is kept.
 *
 * To be removed in 2.0.0 together with the security-crypto dependency.
 */
internal class LegacyConnectionsMigration(
    private val context: Context,
    private val cryptoBox: CryptoBox,
    private val prefsName: String = PREFS_NAME,
) : DataMigration<List<ConnectionConfig>> {

    override suspend fun shouldMigrate(currentData: List<ConnectionConfig>): Boolean {
        val box = prefs(prefsName)
        val oldestPending = !box.getBoolean(KEY_MIGRATED_FLAG, false) &&
            (prefsFileExists(LEGACY_ENC_PREFS) || prefs(PLAIN_LEGACY_PREFS).contains(LEGACY_KEY_CONNECTIONS))
        return currentData.isEmpty() && (box.contains(KEY_PAYLOAD) || oldestPending)
    }

    override suspend fun migrate(currentData: List<ConnectionConfig>): List<ConnectionConfig> {
        val payload = prefs(prefsName).getString(KEY_PAYLOAD, null)
        return if (payload != null) readPayload(payload) else readOldest()
    }

    override suspend fun cleanUp() {
        listOf(prefsName, LEGACY_ENC_PREFS, PLAIN_LEGACY_PREFS).forEach { name ->
            runCatching { context.deleteSharedPreferences(name) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readPayload(base64: String): List<ConnectionConfig> = try {
        val raw = Base64.decode(base64, Base64.NO_WRAP)
        val json = if (raw.startsWith(PLAIN_MARKER)) {
            raw.copyOfRange(PLAIN_MARKER.size, raw.size)
        } else {
            cryptoBox.decrypt(raw)
        }
        ConnectionJson.decode(String(json, Charsets.UTF_8))
    } catch (e: Exception) {
        throw CorruptionException("stored connections (1.1.x format) are unreadable", e)
    }

    /** The pre-1.1.12 stores. Unreadable ones count as empty, as they always did. */
    private fun readOldest(): List<ConnectionConfig> =
        readEncryptedPrefs()?.takeIf { it.isNotEmpty() } ?: readPlainPrefs()

    @Suppress("TooGenericExceptionCaught")
    private fun readEncryptedPrefs(): List<ConnectionConfig>? {
        if (!prefsFileExists(LEGACY_ENC_PREFS)) return null
        return try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val encrypted = EncryptedSharedPreferences.create(
                context,
                LEGACY_ENC_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            encrypted.getString(LEGACY_KEY_CONNECTIONS, null)?.takeIf { it.isNotEmpty() }?.let(ConnectionJson::decode)
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences store unreadable, skipped", e)
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readPlainPrefs(): List<ConnectionConfig> = try {
        prefs(PLAIN_LEGACY_PREFS).getString(LEGACY_KEY_CONNECTIONS, null)?.let(ConnectionJson::decode).orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "plain store unreadable, skipped", e)
        emptyList()
    }

    private fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    // Checked as a file: opening EncryptedSharedPreferences would create a
    // Keystore master key on every fresh install.
    private fun prefsFileExists(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    companion object {
        private const val TAG = "ConnectionsMigration"
        const val PREFS_NAME = "konsole_connections_box"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_MIGRATED_FLAG = "_migrated_from_legacy_v1"
        private const val LEGACY_ENC_PREFS = "konsole_connections_enc"
        private const val PLAIN_LEGACY_PREFS = "konsole_connections"
        private const val LEGACY_KEY_CONNECTIONS = "connections"

        /** Prefix of the plaintext fallback written before 1.1.27. */
        private val PLAIN_MARKER = "PLAIN\u0000".toByteArray(Charsets.UTF_8)
    }
}
