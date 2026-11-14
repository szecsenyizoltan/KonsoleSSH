package hu.billman.konsolessh.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.billman.konsolessh.ui.theme.KonsoleTheme

/**
 * Manages the saved connections. An AppCompatActivity because the edit form
 * is still a DialogFragment, and because AppCompat applies the in-app
 * language on Android 8-12.
 */
class ConnectionEditActivity : AppCompatActivity() {

    private val viewModel: ConnectionsViewModel by viewModels { KonsoleViewModelFactory.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars get light icons whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        supportFragmentManager.setFragmentResultListener(NewConnectionDialog.RESULT_KEY, this) { _, _ ->
            viewModel.refresh()
        }
        setContent {
            KonsoleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ConnectionListScreen(
                    state = state,
                    onBack = ::finish,
                    onAdd = { showDialog(NewConnectionDialog.newForCreate(openTab = false)) },
                    onEdit = { showDialog(NewConnectionDialog.newForEdit(it.id)) },
                    onDelete = { viewModel.delete(it.id) },
                )
            }
        }
    }

    private fun showDialog(dialog: NewConnectionDialog) = dialog.show(supportFragmentManager, DIALOG_TAG)

    private companion object {
        const val DIALOG_TAG = "connection_dialog"
    }
}
