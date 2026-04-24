package hu.billman.konsolessh.model

import android.content.Context
import hu.billman.konsolessh.data.EncryptedPrefsConnectionRepository

/**
 * Backward-compat wrapper a korábbi statikus SavedConnections API-hoz.
 * A valós logika a data/ConnectionRepository + data/EncryptedPrefsConnectionRepository
 * osztályokban él. A call site-ok fokozatosan migrálnak a repository
 * konstruktor-injektált használatára (ViewModel-fázisban).
 */
object SavedConnections {

    private fun repo(context: Context) = EncryptedPrefsConnectionRepository(context)

    fun load(context: Context): MutableList<ConnectionConfig> =
        repo(context).load()

    fun save(context: Context, connections: List<ConnectionConfig>) =
        repo(context).save(connections)

    fun saveOne(context: Context, config: ConnectionConfig) =
        repo(context).saveOne(config)

    fun delete(context: Context, id: String) =
        repo(context).delete(id)
}
