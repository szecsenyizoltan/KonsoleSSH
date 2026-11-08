package hu.billman.konsolessh.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CryptoBox-alapú ConnectionRepository: AES-256-GCM (Android Keystore), saját
 * perzisztenciaformátum.
 *
 * Régebbi tárak (security-crypto EncryptedSharedPreferences, plain-prefs) az
 * első [load]-kor egyszer átmigrálódnak; a régi fájl csak akkor törlődik, ha
 * az új formátum ténylegesen visszaolvasható. A security-crypto függőség
 * ezért még bent van, de csak a migráció használja.
 *
 * Hibakezelés: olvashatatlan tárnál [ConnectionStoreException], írás nélkül;
 * sikertelen titkosításnál sincs titkosítatlan mentés.
 */
class CryptoBoxConnectionRepository(
    context: Context,
    private val cryptoBox: CryptoBox,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Csak tesztekhez cserélendő, hogy ne a valódi tárat írják. */
    private val prefsName: String = PREFS_NAME,
) : ConnectionRepository {

    private val appContext: Context = context.applicationContext
    private val gson = Gson()

    // Read-modify-write (saveOne, delete) must not interleave.
    private val mutex = Mutex()

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override suspend fun load(): List<ConnectionConfig> = io { loadLocked() }

    override suspend fun get(id: String): ConnectionConfig? = io { loadLocked().find { it.id == id } }

    override suspend fun saveOne(config: ConnectionConfig) = io {
        val list = loadLocked().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        write(list)
    }

    override suspend fun delete(id: String) = io {
        write(loadLocked().filterNot { it.id == id })
    }

    private suspend fun <T> io(block: () -> T): T =
        withContext(ioDispatcher) { mutex.withLock { block() } }

    private fun loadLocked(): List<ConnectionConfig> {
        val p = prefs()
        val stored = p.getString(KEY_PAYLOAD, null)
        return when {
            stored != null -> decryptAndParse(stored)
            p.getBoolean(KEY_MIGRATED_FLAG, false) -> emptyList()
            else -> migrateFromLegacy(p)
        }
    }

    // Nothing in the new format yet: try the old stores once. The legacy files
    // are deleted only once the new payload is really there; otherwise the next
    // start retries from the untouched old store.
    private fun migrateFromLegacy(p: SharedPreferences): List<ConnectionConfig> {
        val migrated = readFromLegacyWithoutDeleting()
        val written = migrated.isEmpty() || try {
            write(migrated)
            p.getString(KEY_PAYLOAD, null) != null
        } catch (e: ConnectionStoreException) {
            Log.w(TAG, "migration write failed, legacy store kept", e)
            false
        }
        if (written) {
            deleteLegacyPrefs()
            p.edit(commit = true) { putBoolean(KEY_MIGRATED_FLAG, true) }
        }
        return migrated.sortedByName()
    }

    private fun write(connections: List<ConnectionConfig>) {
        val json = gson.toJson(connections).toByteArray(Charsets.UTF_8)
        // Keystore failures surface as several unrelated types (ProviderException,
        // KeyStoreException, IllegalStateException); none may lead to plaintext.
        val encrypted = try {
            cryptoBox.encrypt(json)
        } catch (e: Exception) {
            throw ConnectionStoreException("encryption failed, nothing was written", e)
        }
        // Synchronous: we are on the IO dispatcher already, and saveOne/delete
        // must not return before the data is on disk (apply() can be lost when
        // the process dies right after).
        val stored = prefs().edit().putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()
        if (!stored) throw ConnectionStoreException("writing the connection store failed")
    }

    private fun decryptAndParse(base64: String): List<ConnectionConfig> {
        val list: List<ConnectionConfig>? = try {
            val raw = Base64.decode(base64, Base64.NO_WRAP)
            // Versions before 1.1.27 could store a plaintext fallback; it stays
            // readable and is encrypted on the next write.
            val json = if (raw.startsWith(PLAIN_MARKER)) {
                raw.copyOfRange(PLAIN_MARKER.size, raw.size)
            } else {
                cryptoBox.decrypt(raw)
            }
            gson.fromJson(String(json, Charsets.UTF_8), LIST_TYPE)
        } catch (e: Exception) {
            Log.e(TAG, "stored connections are unreadable", e)
            throw ConnectionStoreException("stored connections are unreadable", e)
        }
        return list.orEmpty().sortedByName()
    }

    private fun List<ConnectionConfig>.sortedByName() = sortedBy { it.displayName().lowercase() }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    /**
     * Megpróbálja olvasni a régi tárakat (security-crypto EncryptedSharedPreferences,
     * majd ha üres, a még korábbi plain-prefs). **Nem töröl semmit** — a legacy
     * fájlok eltávolítása a hívó feladata, az új formátum visszaolvasása után.
     * Olvashatatlan régi tárnál üres listát ad.
     */
    private fun readFromLegacyWithoutDeleting(): List<ConnectionConfig> {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val legacyPrefs: SharedPreferences = EncryptedSharedPreferences.create(
                appContext, LEGACY_ENC_PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            val json = legacyPrefs.getString(LEGACY_KEY_CONNECTIONS, null)
            if (!json.isNullOrEmpty()) {
                val list: List<ConnectionConfig> = gson.fromJson(json, LIST_TYPE)
                Log.i(TAG, "Read ${list.size} entries from EncryptedSharedPreferences legacy")
                return list
            }
            readPlainLegacyIfPresent()
        } catch (e: Exception) {
            Log.w(TAG, "Legacy read failed (non-fatal)", e)
            readPlainLegacyIfPresent()
        }
    }

    /** A korai, plain-prefs verzió (`konsole_connections`) adatai. Nem töröl. */
    private fun readPlainLegacyIfPresent(): List<ConnectionConfig> {
        val plain = appContext.getSharedPreferences(PLAIN_LEGACY_PREFS, Context.MODE_PRIVATE)
        val json = plain.getString(LEGACY_KEY_CONNECTIONS, null) ?: return emptyList()
        return try {
            val list: List<ConnectionConfig> = gson.fromJson(json, LIST_TYPE)
            Log.i(TAG, "Read ${list.size} entries from plain-prefs legacy")
            list
        } catch (e: Exception) {
            Log.w(TAG, "Plain-legacy parse failed", e)
            emptyList()
        }
    }

    private fun deleteLegacyPrefs() {
        runCatching { appContext.deleteSharedPreferences(LEGACY_ENC_PREFS) }
        runCatching { appContext.deleteSharedPreferences(PLAIN_LEGACY_PREFS) }
    }

    companion object {
        private const val TAG = "CryptoBoxRepo"
        private const val PREFS_NAME = "konsole_connections_box"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_MIGRATED_FLAG = "_migrated_from_legacy_v1"
        private const val LEGACY_ENC_PREFS = "konsole_connections_enc"
        private const val PLAIN_LEGACY_PREFS = "konsole_connections"
        private const val LEGACY_KEY_CONNECTIONS = "connections"

        /** Prefix of the plaintext fallback older versions wrote; read-only now. */
        private val PLAIN_MARKER = "PLAIN\u0000".toByteArray(Charsets.UTF_8)
        private val LIST_TYPE = object : TypeToken<List<ConnectionConfig>>() {}.type
    }
}
