package hu.billman.konsolessh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import hu.billman.konsolessh.model.ConnectionConfig

/**
 * EncryptedSharedPreferences (AES-256-GCM) alapú impl.
 * Ha a titkosított prefs init-je elbukik (pl. Keystore rendellenesség),
 * plain SharedPreferences fallback — ez megvolt az eredeti logikában is.
 *
 * A regisztrált Context-et applicationContext-re redukálja, így
 * Activity-hez kötött példány ne szivárogjon.
 */
class EncryptedPrefsConnectionRepository(
    context: Context,
) : ConnectionRepository {

    private val appContext: Context = context.applicationContext
    private val gson = Gson()

    private fun getPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "EncryptedSharedPreferences init failed, using plain prefs", e)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Plain-text legacy prefs → encrypted migráció első olvasáskor. */
    private fun migrateLegacyIfNeeded(encPrefs: SharedPreferences) {
        val legacy = appContext.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
        val legacyJson = legacy.getString(KEY_CONNECTIONS, null) ?: return
        if (encPrefs.getString(KEY_CONNECTIONS, null) == null) {
            encPrefs.edit().putString(KEY_CONNECTIONS, legacyJson).apply()
        }
        legacy.edit().remove(KEY_CONNECTIONS).apply()
    }

    override fun load(): MutableList<ConnectionConfig> {
        val prefs = getPrefs()
        migrateLegacyIfNeeded(prefs)
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ConnectionConfig>>() {}.type
            val list: MutableList<ConnectionConfig> = gson.fromJson(json, type)
            list.sortedBy { it.displayName().lowercase() }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    override fun save(connections: List<ConnectionConfig>) {
        getPrefs().edit().putString(KEY_CONNECTIONS, gson.toJson(connections)).apply()
    }

    override fun saveOne(config: ConnectionConfig) {
        val list = load()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        save(list)
    }

    override fun delete(id: String) {
        val list = load()
        list.removeAll { it.id == id }
        save(list)
    }

    companion object {
        private const val TAG = "EncryptedConnRepo"
        private const val PREFS_NAME = "konsole_connections_enc"
        private const val PREFS_NAME_LEGACY = "konsole_connections"
        private const val KEY_CONNECTIONS = "connections"
    }
}
