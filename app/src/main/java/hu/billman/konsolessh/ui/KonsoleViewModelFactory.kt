package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import hu.billman.konsolessh.AppContainer

/**
 * Manuális ViewModelProvider.Factory. Az AppContainer-ből szerzi be a
 * dependencyket — így a ViewModel-ek konstruktor-injektáltak, tesztekben
 * FakeConnectionRepository-val példányosíthatóak.
 */
class KonsoleViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(ConnectionsViewModel::class.java) ->
                ConnectionsViewModel(container.connectionRepository) as T
            modelClass.isAssignableFrom(NewConnectionViewModel::class.java) ->
                NewConnectionViewModel() as T
            else -> error("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
