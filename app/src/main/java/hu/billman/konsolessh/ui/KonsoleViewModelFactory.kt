package hu.billman.konsolessh.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import hu.billman.konsolessh.AppContainer

/**
 * Hands the [AppContainer]'s dependencies to the ViewModels' constructors,
 * so tests can construct the ViewModels with fakes instead.
 */
class KonsoleViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(MainViewModel::class.java) ->
            MainViewModel(
                container.sessionManager,
                container.connectionRepository,
                container.settingsRepository,
                container.problemReports,
            )
        modelClass.isAssignableFrom(ConnectionsViewModel::class.java) ->
            ConnectionsViewModel(container.connectionRepository)
        modelClass.isAssignableFrom(NewConnectionViewModel::class.java) ->
            NewConnectionViewModel(container.connectionRepository, container.keyProvisioner)
        else -> error("Unknown ViewModel class: ${modelClass.name}")
    } as T

    companion object {
        fun from(context: Context) = KonsoleViewModelFactory(AppContainer.from(context))
    }
}
