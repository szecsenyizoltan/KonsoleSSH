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

/**
 * CryptoBox-alapú ConnectionRepository: AES-256-GCM (Android Keystore), saját
 * perzisztenciaformátum, `androidx.security:security-crypto` alpha dep-től
 * független *napi* működés.
 *
 * A régi [EncryptedPrefsConnectionRepository] (security-crypto alpha) és még
 * régebbi plain-prefs adatok automatikusan átmigrálódnak az első [load]-kor:
 *   1. Új prefs-fájlban ([PREFS_NAME]) tároljuk a titkosított JSON-t.
 *   2. Első olvasáskor, ha ez üres és a migráció még nem futott,
 *      megpróbáljuk olvasni a régi `konsole_connections_enc` fájlt
 *      az EncryptedSharedPreferences API-jával, és átmásoljuk az új
 *      formátumba. Siker esetén törlődik a régi fájl.
 *   3. A migráció idempotens: egy flag (`_migrated_from_legacy_v1`)
 *      megakadályozza az ismételt lefutást.
 *
 * A security-crypto dep ezért még bent marad a projektben — csak a migrációs
 * kód hívja, a napi CRUD már a [cryptoBox] impl-en megy. Egy későbbi release
 * a migrációs ágat is kiveheti (akkor a régi felhasználók adatai elveszhetnek,
 * ezért ezt csak több hónapos beágyazódás után).
 */
class CryptoBoxConnectionRepository(
    context: Context,
    private val cryptoBox: CryptoBox,
) : ConnectionRepository {

    private val appContext: Context = context.applicationContext
    private val gson = Gson()

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): MutableList<ConnectionConfig> {
        val p = prefs()
        p.getString(KEY_PAYLOAD, null)?.let { return decryptAndParse(it) }

        // Új formátumban nincs adat. Egyszer próbáljuk a régi forrásokat.
        if (!p.getBoolean(KEY_MIGRATED_FLAG, false)) {
            val migrated = readFromLegacyWithoutDeleting()
            if (migrated.isNotEmpty()) {
                save(migrated)
                // Csak akkor töröljük a legacy fájlokat és rakjuk le a flaget,
                // ha az új formátumban a save ténylegesen megjelent. Ha a save
                // bármilyen okból nem sikerült, a legacy forrás érintetlen
                // marad és a következő induláskor újra próbálhatjuk.
                if (p.getString(KEY_PAYLOAD, null) != null) {
                    deleteLegacyPrefs()
                    p.edit { putBoolean(KEY_MIGRATED_FLAG, true) }
                }
                return migrated.toMutableList()
            }
            // Nincs migrálható adat: takarítsuk el a (valószínűleg üres vagy
            // hozzáférhetetlen) legacy fájlokat, és jelöljük a migrációt
            // lefutottnak, hogy ne próbáljuk minden induláskor újra.
            deleteLegacyPrefs()
            p.edit { putBoolean(KEY_MIGRATED_FLAG, true) }
        }
        return mutableListOf()
    }

    override fun save(connections: List<ConnectionConfig>) {
        val json = gson.toJson(connections).toByteArray(Charsets.UTF_8)
        val payload = try {
            Base64.encodeToString(cryptoBox.encrypt(json), Base64.NO_WRAP)
        } catch (e: Exception) {
            // Plaintext-fallback csak adatveszteség megelőzésére — a user
            // jelszavai/kulcsai ezzel rendszerszintű titkosítás NÉLKÜL
            // tárolódnak. Log.wtf-ként (CryptoFailureSink-en át) jelezzük,
            // hogy a Keystore-integráció meghibásodott; Crashlytics / bug-
            // report / override-olt handler mind látja a riadót.
            CryptoFailureSink.report(
                TAG,
                "AES-GCM encrypt failed — saving connection list in plaintext fallback",
                e,
            )
            Base64.encodeToString(PLAIN_MARKER + json, Base64.NO_WRAP)
        }
        prefs().edit { putString(KEY_PAYLOAD, payload) }
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

    private fun decryptAndParse(base64: String): MutableList<ConnectionConfig> = try {
        val raw = Base64.decode(base64, Base64.NO_WRAP)
        val json = if (raw.size >= PLAIN_MARKER.size &&
            raw.copyOfRange(0, PLAIN_MARKER.size).contentEquals(PLAIN_MARKER)
        ) {
            raw.copyOfRange(PLAIN_MARKER.size, raw.size)
        } else {
            cryptoBox.decrypt(raw)
        }
        val text = String(json, Charsets.UTF_8)
        val type = object : TypeToken<MutableList<ConnectionConfig>>() {}.type
        val list: MutableList<ConnectionConfig> = gson.fromJson(text, type)
        list.sortedBy { it.displayName().lowercase() }.toMutableList()
    } catch (e: Exception) {
        Log.e(TAG, "decrypt/parse failed — returning empty list", e)
        mutableListOf()
    }

    /**
     * Megpróbálja olvasni a régi tárakat (security-crypto EncryptedSharedPreferences,
     * majd ha üres, a még korábbi plain-prefs). **Nem töröl semmit** — a legacy
     * fájlok eltávolítása a hívó feladata, az új formátumú save sikeres
     * verifikálása után. Minden kivételt elnyel; ha a régi tár nem olvasható
     * (pl. Keystore-inkompatibilitás), üres listát ad vissza, de nem crash-el.
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
                val type = object : TypeToken<MutableList<ConnectionConfig>>() {}.type
                val list: MutableList<ConnectionConfig> = gson.fromJson(json, type)
                Log.i(TAG, "Read ${list.size} entries from EncryptedSharedPreferences legacy")
                return list
            }
            readPlainLegacyIfPresent()
        } catch (e: Exception) {
            Log.w(TAG, "Legacy read failed (non-fatal)", e)
            readPlainLegacyIfPresent()
        }
    }

    /**
     * Még a korai, plain-prefs verzió (`konsole_connections`) adatait is
     * megpróbáljuk olvasni. Nem töröl — a hívó dolga.
     */
    private fun readPlainLegacyIfPresent(): List<ConnectionConfig> {
        val plain = appContext.getSharedPreferences(PLAIN_LEGACY_PREFS, Context.MODE_PRIVATE)
        val json = plain.getString(LEGACY_KEY_CONNECTIONS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<MutableList<ConnectionConfig>>() {}.type
            val list: MutableList<ConnectionConfig> = gson.fromJson(json, type)
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
        /** Plain-fallback prefix; akkor kerül a payload elé, ha az encrypt csődöt mondana. */
        private val PLAIN_MARKER = "PLAIN ".toByteArray(Charsets.UTF_8)
    }
}
