package hu.billman.konsolessh.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import hu.billman.konsolessh.AppContainer

/**
 * Hands the [AppContainer]'s dependencies to the ViewModels' constructors,
 * so tests can construct the ViewModels with fakes instead.
 */
object KonsoleViewModelFactory {

    fun from(context: Context): ViewModelProvider.Factory = create(AppContainer.from(context))

    fun create(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            MainViewModel(
                container.sessionManager,
                container.connectionRepository,
                container.settingsRepository,
            )
        }
        initializer { ConnectionsViewModel(container.connectionRepository) }
        initializer { NewConnectionViewModel(container.connectionRepository, container.keyProvisioner) }
        initializer {
            SyncViewModel(container.syncManager, container.settingsRepository, container.connectionRepository)
        }
    }
}
