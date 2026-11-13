package hu.billman.konsolessh.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/** Small user settings. */
interface SettingsRepository {
    /** Terminal font size in sp; 0 means none saved, the terminal uses its own default. */
    val fontSizeSp: Flow<Float>

    suspend fun setFontSizeSp(value: Float)
}

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {

    override val fontSizeSp: Flow<Float> = store.data
        // An unreadable settings file must not take the terminal down: fall back to defaults.
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "settings unreadable", e)
            emit(emptyPreferences())
        }
        .map { it[FONT_SIZE] ?: 0f }
        .distinctUntilChanged()

    override suspend fun setFontSizeSp(value: Float) {
        // Losing a font size is better than crashing on a full or broken disk.
        try {
            store.edit { it[FONT_SIZE] = value }
        } catch (e: IOException) {
            Log.w(TAG, "font size not saved", e)
        }
    }

    companion object {
        private const val TAG = "Settings"
        private const val STORE_NAME = "settings"

        /** Versions up to 1.1.30 kept the settings in SharedPreferences under this name. */
        internal const val LEGACY_PREFS_NAME = "settings"
        private val FONT_SIZE = floatPreferencesKey("font_size")

        /**
         * The app's settings store. Only one may exist per process. On first use the
         * old SharedPreferences are copied in under the same key and then deleted.
         */
        fun create(context: Context, scope: CoroutineScope): DataStoreSettingsRepository {
            val appContext = context.applicationContext
            return DataStoreSettingsRepository(
                createStore(appContext, scope, LEGACY_PREFS_NAME) { appContext.preferencesDataStoreFile(STORE_NAME) },
            )
        }

        /** A corrupt file is replaced with empty settings instead of failing every read and write. */
        internal fun createStore(
            context: Context,
            scope: CoroutineScope,
            legacyPrefsName: String,
            produceFile: () -> File,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { e ->
                Log.w(TAG, "settings file corrupt, starting empty", e)
                emptyPreferences()
            },
            migrations = listOf(SharedPreferencesMigration(context, legacyPrefsName)),
            scope = scope,
            produceFile = produceFile,
        )
    }
}
