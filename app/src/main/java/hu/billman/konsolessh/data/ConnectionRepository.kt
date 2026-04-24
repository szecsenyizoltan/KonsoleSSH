package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig

/**
 * Perzisztens tároló az elmentett SSH-kapcsolatokhoz.
 *
 * Az interface lehetővé teszi a ViewModel-ek és használati esetek
 * egységtesztelését anélkül, hogy valós (Encrypted)SharedPreferences-re
 * vagy Android-kontextusra lenne szükség (lásd FakeConnectionRepository).
 *
 * A jelenlegi valós implementáció a CryptoBoxConnectionRepository.
 */
interface ConnectionRepository {
    fun load(): MutableList<ConnectionConfig>
    fun save(connections: List<ConnectionConfig>)
    fun saveOne(config: ConnectionConfig)
    fun delete(id: String)
}
