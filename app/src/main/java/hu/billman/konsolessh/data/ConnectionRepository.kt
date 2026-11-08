package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig

/**
 * Perzisztens tároló az elmentett SSH-kapcsolatokhoz. Main-safe: minden
 * művelet suspend, az impl maga vált háttérszálra.
 *
 * Olvashatatlan tárnál (sérült adat, Keystore-hiba) [ConnectionStoreException]
 * jön, és az írások sem futnak le: egy üresnek látszó lista visszaírása
 * végleg felülírná a meglévő profilokat.
 */
interface ConnectionRepository {
    suspend fun load(): List<ConnectionConfig>
    suspend fun get(id: String): ConnectionConfig?
    suspend fun saveOne(config: ConnectionConfig)
    suspend fun delete(id: String)
}

/** A tár nem olvasható vagy nem írható; a meglévő adat érintetlen maradt. */
class ConnectionStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)
