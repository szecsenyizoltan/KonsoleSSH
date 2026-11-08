package hu.billman.konsolessh.data

import android.content.Context
import androidx.core.content.edit

/** Kis felhasználói beállítások (betűméret). */
interface SettingsRepository {
    /** 0 = nincs mentett érték, a terminál a saját alapértékét használja. */
    var fontSizeSp: Float
}

class PrefsSettingsRepository(context: Context) : SettingsRepository {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 0f)
        set(value) = prefs.edit { putFloat(KEY_FONT_SIZE, value) }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_FONT_SIZE = "font_size"
    }
}
