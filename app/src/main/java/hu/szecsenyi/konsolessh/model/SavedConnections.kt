package hu.szecsenyi.konsolessh.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object SavedConnections {
    private const val PREFS_NAME = "konsole_connections"
    private const val KEY_CONNECTIONS = "connections"
    private val gson = Gson()

    fun load(context: Context): MutableList<ConnectionConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
