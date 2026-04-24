package hu.billman.konsolessh.model

import android.content.Context
import hu.billman.konsolessh.AppContainer

/**
 * Backward-compat wrapper a korábbi statikus SavedConnections API-hoz.
 *
 * A valós logika a `data/ConnectionRepository` interfész mögött van. Az
 * aktuális (Fázis 7 utáni) impl a `CryptoBoxConnectionRepository`, amit
 * Fázis 8-tól az `AppContainer` tartja egyetlen példányban — így minden
 * hívás ugyanazt a repository-t használja, nem hoz létre új
 * `AesGcmKeystoreBox`-ot minden egyes read/write-nál.
 *
 * A jövőben (ViewModel-migrációk végeztével) ez a wrapper törölhető.
 */
object SavedConnections {

    fun load(context: Context): MutableList<ConnectionConfig> =
        AppContainer.from(context).connectionRepository.load()

    fun save(context: Context, connections: List<ConnectionConfig>) =
        AppContainer.from(context).connectionRepository.save(connections)

    fun saveOne(context: Context, config: ConnectionConfig) =
        AppContainer.from(context).connectionRepository.saveOne(config)

    fun delete(context: Context, id: String) =
        AppContainer.from(context).connectionRepository.delete(id)
}
