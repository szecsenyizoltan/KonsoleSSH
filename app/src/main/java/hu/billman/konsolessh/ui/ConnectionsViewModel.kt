package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.ConnectionStoreException
import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A mentett kapcsolatok listája (kezelő képernyő, fülválasztó). */
class ConnectionsViewModel(
    private val repository: ConnectionRepository,
) : ViewModel() {

    data class UiState(
        val connections: List<ConnectionConfig> = emptyList(),
        /** A tár nem olvasható/írható; a lista ilyenkor nem frissül. */
        val storeError: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                _state.value = UiState(repository.load())
            } catch (e: ConnectionStoreException) {
                _state.update { it.copy(storeError = true) }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.delete(id)
                _state.value = UiState(repository.load())
            } catch (e: ConnectionStoreException) {
                _state.update { it.copy(storeError = true) }
            }
        }
    }
}
