package hu.billman.konsolessh.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import hu.billman.konsolessh.AppContainer
import hu.billman.konsolessh.ui.sync.SyncActions
import hu.billman.konsolessh.ui.sync.SyncScreen
import hu.billman.konsolessh.ui.sync.SyncViewModel

/**
 * The single activity's screens. [main] is the root; Back on it is left to
 * the activity, which moves the app to the background so the sessions live
 * on. Every other screen gets its own ViewModels, created when it is opened
 * and cleared when it is closed, as when it was an activity of its own.
 */
@Composable
fun KonsoleNavigation(main: @Composable (navigate: (Route) -> Unit) -> Unit) {
    val backStack = rememberNavBackStack(Route.Main)
    val navigate: (Route) -> Unit = { route -> if (backStack.lastOrNull() != route) backStack.add(route) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Route.Main> { main(navigate) }
            entry<Route.Connections> { ConnectionsDestination(onBack = back) }
            entry<Route.Sync> { SyncDestination(onBack = back) }
        },
    )
}

@Composable
private fun ConnectionsDestination(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: ConnectionsViewModel = viewModel(factory = KonsoleViewModelFactory.from(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var form by rememberSaveable(stateSaver = FormRequest.Saver) { mutableStateOf<FormRequest?>(null) }
    ConnectionListScreen(
        state = state,
        onBack = onBack,
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

@Composable
private fun SyncDestination(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SyncViewModel = viewModel(factory = KonsoleViewModelFactory.from(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consent by viewModel.consent.collectAsStateWithLifecycle()
    // Google's own consent screen; the action that needed it runs again once granted.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        val failure = if (granted) null else AppContainer.from(context).driveAuthorizer.failureFrom(result.data)
        viewModel.onConsentResult(granted, failure)
    }
    LaunchedEffect(consent) {
        consent?.let {
            viewModel.onConsentShown()
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }
    SyncScreen(
        state = state,
        actions = SyncActions(
            onBack = onBack,
            onConnect = viewModel::connect,
            onEnable = viewModel::enable,
            onSyncNow = viewModel::syncNow,
            onDisable = viewModel::disable,
            onGrant = viewModel::grant,
        ),
    )
}
