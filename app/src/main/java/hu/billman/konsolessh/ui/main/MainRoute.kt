package hu.billman.konsolessh.ui.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.R
import hu.billman.konsolessh.service.SshForegroundService
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.Route
import hu.billman.konsolessh.ui.common.ToastHostState
import hu.billman.konsolessh.ui.form.ConnectionFormDialog
import hu.billman.konsolessh.ui.form.FormRequest
import hu.billman.konsolessh.ui.keybar.Keybar
import hu.billman.konsolessh.ui.sync.SyncViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The main screen with everything it needs from the platform: tab strip,
 * terminal pages and key bar, the tab picker and the connection form it
 * opens, and the dialogs over them. It draws [MainViewModel]'s state and
 * sends it the user's commands. [focus] is shared with the activity, which
 * routes hardware keys to the current terminal.
 */
@Composable
fun MainRoute(
    viewModel: MainViewModel,
    syncViewModel: SyncViewModel,
    focus: TerminalFocus,
    navigate: (Route) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tabs = state.sessions.tabs
    val pageKeys = remember(tabs.map { it.id }) { PageKey.pages(tabs.map { it.id }) }
    val pagerState = rememberPagerState { pageKeys.size }
    // Pager animations need the composition's frame clock, which lifecycleScope lacks.
    val scope = rememberCoroutineScope()
    val overlay = remember { MainOverlayState() }
    // Saved, so the picker and the form it opened survive a configuration change.
    var pickingTab by rememberSaveable { mutableStateOf(false) }
    var form by rememberSaveable(stateSaver = FormRequest.Saver) { mutableStateOf<FormRequest?>(null) }
    focus.pager = pagerState
    focus.pages = pageKeys

    val context = LocalContext.current
    MainEffects(viewModel)
    PagerFollowsTabs(state, pageKeys, pagerState, onTabSelected = viewModel::onTabSelected)

    val keybarHost = rememberKeybarHost(viewModel, focus, overlay.toasts)
    MainScreen(
        tabs = tabs,
        pages = pageKeys,
        pager = pagerState,
        actions = MainScreenActions(
            onSelectPage = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
            onCloseTab = viewModel::requestCloseTab,
            onRenameTab = { overlay.renaming = it },
            onNewTab = { pickingTab = true },
            onZoom = { delta ->
                focus.currentTerminal()?.let { terminal ->
                    terminal.zoom(delta)
                    viewModel.saveFontSize(terminal.fontSize)
                }
            },
            onCloseCurrentTab = { focus.currentTabId()?.let(viewModel::requestCloseTab) },
            onManageConnections = { navigate(Route.Connections) },
            onSync = { navigate(Route.Sync) },
            onLanguage = { overlay.pickingLanguage = true },
            onPrivacyPolicy = { openPrivacyPolicy(context, overlay.toasts) },
            onExit = viewModel::requestCloseApp,
        ),
        page = { page ->
            val register: (TerminalView?) -> Unit = { view -> focus.register(page, view) }
            when (page) {
                is PageKey.Tab -> SshTerminalPage(page.id, viewModel, register)
                else -> StaticTerminalPage(page, register)
            }
        },
        keybar = {
            val modifiers by viewModel.modifiers.state.collectAsStateWithLifecycle()
            Keybar(modifiers, host = keybarHost)
        },
    )
    MainOverlays(state, overlay, viewModel, syncViewModel, onRestore = { navigate(Route.Sync) })
    if (pickingTab) {
        TabPicker(viewModel, pagerState, scope, onForm = { form = it }, onDismiss = { pickingTab = false })
    }
    form?.let { request ->
        ConnectionFormDialog(
            request = request,
            onSaved = { id ->
                form = null
                if (request.openTab) viewModel.openTab(id)
            },
            onDismiss = { form = null },
        )
    }
}

/** Keeps the pager on the right page when tabs open and close. */
@Composable
private fun PagerFollowsTabs(
    state: MainViewModel.UiState,
    pageKeys: List<PageKey>,
    pagerState: PagerState,
    onTabSelected: () -> Unit,
) {
    // With the last tab closed, the pager would stay on the cheat sheet that took
    // the tab's place; the welcome page comes back instead.
    val noTabs = state.sessions.tabs.isEmpty()
    var hadTabs by remember { mutableStateOf(false) }
    LaunchedEffect(noTabs) {
        if (noTabs && hadTabs) pagerState.scrollToPage(0)
        hadTabs = !noTabs
    }

    // A freshly opened tab is shown at once.
    val selectTab = state.selectTab
    LaunchedEffect(selectTab, pageKeys) {
        val index = selectTab?.let { pageKeys.indexOf(PageKey.Tab(it)) } ?: return@LaunchedEffect
        if (index >= 0) {
            pagerState.scrollToPage(index)
            onTabSelected()
        }
    }
}

/** What the ViewModel asks of the platform while the screen is visible. */
@Composable
private fun MainEffects(viewModel: MainViewModel) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, activity, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Started from the visible activity, as Android requires; the service stops itself.
            launch {
                viewModel.state.map { it.serviceNeeded }.distinctUntilChanged().collect { needed ->
                    if (needed) SshForegroundService.start(activity)
                }
            }
            launch { viewModel.state.first { it.exit }.also { finishApp(activity) } }
        }
    }
}

private fun finishApp(activity: Activity) {
    SshForegroundService.stop(activity)
    activity.finishAffinity()
}

@Composable
private fun rememberKeybarHost(viewModel: MainViewModel, focus: TerminalFocus, toasts: ToastHostState): MainKeybarHost {
    val context = LocalContext.current
    val view = LocalView.current
    val uploads = remember(context, viewModel) { UploadController(context, viewModel) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uploads.upload(focus.currentTabId(), uri)
    }
    return remember(viewModel, focus, toasts, view) {
        MainKeybarHost(focus, viewModel, toasts, view, pickFile = { pickFile.launch(arrayOf("*/*")) })
    }
}

@Composable
private fun TabPicker(
    viewModel: MainViewModel,
    pagerState: PagerState,
    scope: CoroutineScope,
    onForm: (FormRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    TabPickerSheet(
        mainViewModel = viewModel,
        currentPage = pagerState.currentPage,
        choices = TabPickerChoices(
            onNewConnection = { onForm(FormRequest(editId = null, openTab = true)) },
            // The picker counts tabs only; the welcome page comes before them.
            onSelectTab = { index -> scope.launch { pagerState.animateScrollToPage(index + 1) } },
            onOpen = { viewModel.openTab(it.id) },
            onEdit = { onForm(FormRequest(editId = it.id, openTab = false)) },
        ),
        onDismiss = onDismiss,
    )
}

/** Google Play requires the policy to be reachable from inside the app. */
private fun openPrivacyPolicy(context: Context, toasts: ToastHostState) {
    val page = Intent(Intent.ACTION_VIEW, context.getString(R.string.privacy_policy_url).toUri())
    try {
        context.startActivity(page)
    } catch (e: ActivityNotFoundException) {
        toasts.show(context.getString(R.string.err_no_browser))
    }
}
