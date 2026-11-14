package hu.billman.konsolessh.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.billman.konsolessh.ui.theme.KonsoleTheme

/**
 * Manages the saved connections. An AppCompatActivity because AppCompat
 * applies the in-app language on Android 8-12.
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
        setContent {
            KonsoleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var form by rememberSaveable(stateSaver = FormRequest.Saver) { mutableStateOf<FormRequest?>(null) }
                ConnectionListScreen(
                    state = state,
                    onBack = ::finish,
                    onAdd = { form = FormRequest(editId = null, openTab = false) },
                    onEdit = { form = FormRequest(editId = it.id, openTab = false) },
                    onDelete = { viewModel.delete(it.id) },
                )
                form?.let { request ->
                    ConnectionFormDialog(
                        request = request,
                        onSaved = {
                            form = null
                            viewModel.refresh()
                        },
                        onDismiss = { form = null },
                    )
                }
            }
        }
    }
}
