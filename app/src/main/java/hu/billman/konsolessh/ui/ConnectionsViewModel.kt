package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A mentett kapcsolatok listáját tartja egy [StateFlow]-ban. A repository
 * hívások (SharedPreferences.edit + AES-GCM dekódolás) néhány millisekundumos
 * nagyságrendűek, ezért a Main-szálon futnak — külön background-dispatcher
 * nem indokolt. Ha ez a jövőben lassúvá válna, a repo mögé tehető egy
 * coroutine-wrapper.
 */
class ConnectionsViewModel(
    private val repository: ConnectionRepository,
) : ViewModel() {

    private val _connections = MutableStateFlow<List<ConnectionConfig>>(emptyList())
    val connections: StateFlow<List<ConnectionConfig>> = _connections.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _connections.value = repository.load()
    }

    fun saveOne(config: ConnectionConfig) {
        repository.saveOne(config)
        refresh()
    }

    fun delete(id: String) {
        repository.delete(id)
        refresh()
    }
}
