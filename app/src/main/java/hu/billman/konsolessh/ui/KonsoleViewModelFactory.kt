package hu.billman.konsolessh.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import hu.billman.konsolessh.AppContainer
import hu.billman.konsolessh.ssh.SshKeyGenerator
import hu.billman.konsolessh.ui.connections.ConnectionsViewModel
import hu.billman.konsolessh.ui.form.NewConnectionViewModel
import hu.billman.konsolessh.ui.sync.SyncViewModel

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
        initializer {
            NewConnectionViewModel(container.connectionRepository, container.keyProvisioner, SshKeyGenerator)
        }
        initializer {
            SyncViewModel(container.syncManager, container.settingsRepository, container.connectionRepository)
        }
    }
}
