package hu.billman.konsolessh.model

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SavedConnections {
    private const val PREFS_NAME        = "konsole_connections_enc"
    private const val PREFS_NAME_LEGACY = "konsole_connections"
    private const val KEY_CONNECTIONS   = "connections"
    private val gson = Gson()

    private fun getPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            android.util.Log.e("SavedConnections", "EncryptedSharedPreferences init failed, using plain prefs", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** Migrate existing plain-text prefs to encrypted on first run. */
    private fun migrateLegacyIfNeeded(context: Context, encPrefs: SharedPreferences) {
        val legacy = context.getSharedPreferences(PREFS_NAME_LEGACY, Context.MODE_PRIVATE)
        val legacyJson = legacy.getString(KEY_CONNECTIONS, null) ?: return
        if (encPrefs.getString(KEY_CONNECTIONS, null) == null) {
            encPrefs.edit().putString(KEY_CONNECTIONS, legacyJson).apply()
        }
        legacy.edit().remove(KEY_CONNECTIONS).apply()
    }

    fun load(context: Context): MutableList<ConnectionConfig> {
        val prefs = getPrefs(context)
        migrateLegacyIfNeeded(context, prefs)
        val json = prefs.getString(KEY_CONNECTIONS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ConnectionConfig>>() {}.type
            val list: MutableList<ConnectionConfig> = gson.fromJson(json, type)
            list.sortedBy { it.displayName().lowercase() }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, connections: List<ConnectionConfig>) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_CONNECTIONS, gson.toJson(connections)).apply()
    }

    fun saveOne(context: Context, config: ConnectionConfig) {
        val list = load(context)
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        val list = load(context)
        list.removeAll { it.id == id }
        save(context, list)
    }
}
