package hu.billman.konsolessh.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import hu.billman.konsolessh.ui.main.MainRoute
import hu.billman.konsolessh.ui.main.MainViewModel
import hu.billman.konsolessh.ui.main.TerminalFocus
import hu.billman.konsolessh.ui.sync.SyncViewModel
import hu.billman.konsolessh.ui.theme.KonsoleTheme

/**
 * The app's only activity: a host for [KonsoleNavigation], whose root is
 * [MainRoute]. It keeps what only an activity can do: the window setup, the
 * runtime permissions, Back on the root screen and the hardware keys. An
 * AppCompatActivity because AppCompat applies the in-app language on
 * Android 8-12.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels { KonsoleViewModelFactory.from(this) }
    private val syncViewModel: SyncViewModel by viewModels { KonsoleViewModelFactory.from(this) }

    /** Shared with the main screen, which registers its terminals here. */
    val focus = TerminalFocus()

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars get light icons whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestRuntimePermissionsIfNeeded()

        // With targetSdk 36+ onBackPressed is no longer called. Back moves the
        // app to the background so the sessions stay alive.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setContent {
            KonsoleTheme {
                KonsoleNavigation { navigate -> MainRoute(viewModel, syncViewModel, focus, navigate) }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = focus.dispatchKey(event) || super.dispatchKeyEvent(event)
}
