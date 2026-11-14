package hu.billman.konsolessh.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.billman.konsolessh.ui.theme.KonsoleTheme

/**
 * The Google Drive sync screen. An AppCompatActivity because AppCompat
 * applies the in-app language on Android 8-12.
 */
class SyncActivity : AppCompatActivity() {

    private val viewModel: SyncViewModel by viewModels { KonsoleViewModelFactory.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            KonsoleTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val consent by viewModel.consent.collectAsStateWithLifecycle()
                // Google's own consent screen; the action that needed it runs again once granted.
                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult(),
                ) { result -> viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK) }
                LaunchedEffect(consent) {
                    consent?.let {
                        viewModel.onConsentShown()
                        consentLauncher.launch(IntentSenderRequest.Builder(it).build())
                    }
                }
                SyncScreen(
                    state = state,
                    actions = SyncActions(
                        onBack = ::finish,
                        onConnect = viewModel::connect,
                        onEnable = viewModel::enable,
                        onSyncNow = viewModel::syncNow,
                        onDisable = viewModel::disable,
                        onGrant = viewModel::grant,
                    ),
                )
            }
        }
    }
}
