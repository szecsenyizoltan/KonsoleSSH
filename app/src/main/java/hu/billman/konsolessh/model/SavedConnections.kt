package hu.billman.konsolessh.model

import android.content.Context
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.CryptoBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository

/**
 * Backward-compat wrapper a korábbi statikus SavedConnections API-hoz.
 *
 * A valós logika a `data/ConnectionRepository` interfész mögött van; az
 * aktuális impl a `CryptoBoxConnectionRepository` (AES-256-GCM Android
 * Keystore, `androidx.security:security-crypto` alpha dep-től független
 * napi működés). Az első [load] automatikusan átmigrálja a régebbi
 * `EncryptedSharedPreferences`- és a még régebbi plain-prefs-adatokat.
 *
 * A call site-ok fokozatosan migrálnak a repository konstruktor-injektált
 * használatára (ViewModel-fázisban).
 */
object SavedConnections {

    private val sharedCryptoBox: CryptoBox by lazy { AesGcmKeystoreBox() }

    private fun repo(context: Context) =
        CryptoBoxConnectionRepository(context, sharedCryptoBox)

    fun load(context: Context): MutableList<ConnectionConfig> =
        repo(context).load()

    fun save(context: Context, connections: List<ConnectionConfig>) =
        repo(context).save(connections)

    fun saveOne(context: Context, config: ConnectionConfig) =
        repo(context).saveOne(config)

    fun delete(context: Context, id: String) =
        repo(context).delete(id)
}
