package hu.billman.konsolessh.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.billman.konsolessh.R
import hu.billman.konsolessh.service.SshForegroundService
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.keybar.Keybar
import hu.billman.konsolessh.ui.keybar.KeybarHost
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import hu.billman.konsolessh.terminal.Modifier as Key

/**
 * The app's only activity. It hosts [KonsoleNavigation] and draws its root,
 * the main screen: tab strip, terminal pages, key bar. It owns no state of
 * its own beyond what is on screen: it draws [MainViewModel]'s state and
 * sends it the user's commands. An AppCompatActivity because AppCompat
 * applies the in-app language on Android 8-12.
 */
class MainActivity : AppCompatActivity(), KeybarHost {

    private val viewModel: MainViewModel by viewModels { KonsoleViewModelFactory.from(this) }
    private val syncViewModel: SyncViewModel by viewModels { KonsoleViewModelFactory.from(this) }
    private lateinit var uploads: UploadController
    private val toasts = ToastHostState()

    /** The pager, once composed; the key bar, zoom and hardware keys act on its current page. */
    private var pager: PagerState? = null
    private var pages: List<PageKey> = emptyList()

    /** The terminal view of each page that is currently composed. */
    private val terminals = mutableMapOf<PageKey, TerminalView>()

    /** The tab whose rename dialog is open. */
    private var renaming by mutableStateOf<TabState?>(null)
    private var pickingLanguage by mutableStateOf(false)

    private val pickFileForUpload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uploads.upload(currentTabId(), uri)
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars get light icons whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestRuntimePermissionsIfNeeded()
        uploads = UploadController(this, viewModel)

        // With targetSdk 36+ onBackPressed is no longer called. Back moves the
        // app to the background so the sessions stay alive.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setContent { KonsoleTheme { KonsoleNavigation { navigate -> Screen(navigate) } } }
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Started from the visible activity, as Android requires; the service stops itself.
                launch {
                    viewModel.state.map { it.serviceNeeded }.distinctUntilChanged().collect { needed ->
                        if (needed) SshForegroundService.start(this@MainActivity)
                    }
                }
                launch {
                    viewModel.state.map { it.reportToShare }.distinctUntilChanged().collect { report ->
                        report ?: return@collect
                        ProblemReportUi.share(this@MainActivity, report)
                        viewModel.onReportShared()
                    }
                }
                launch { viewModel.state.first { it.exit }.also { finishApp() } }
            }
        }
    }

    @Composable
    private fun Screen(navigate: (Route) -> Unit) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val tabs = state.sessions.tabs
        val pageKeys = remember(tabs.map { it.id }) { PageKey.pages(tabs.map { it.id }) }
        val pagerState = rememberPagerState { pageKeys.size }
        // Pager animations need the composition's frame clock, which lifecycleScope lacks.
        val scope = rememberCoroutineScope()
        // Saved, so the picker and the form it opened survive a configuration change.
        var pickingTab by rememberSaveable { mutableStateOf(false) }
        var form by rememberSaveable(stateSaver = FormRequest.Saver) { mutableStateOf<FormRequest?>(null) }
        pager = pagerState
        pages = pageKeys

        // With the last tab closed, the pager would stay on the cheat sheet that took
        // the tab's place; the welcome page comes back instead.
        var hadTabs by remember { mutableStateOf(false) }
        LaunchedEffect(tabs.isEmpty()) {
            if (tabs.isEmpty() && hadTabs) pagerState.scrollToPage(0)
            hadTabs = tabs.isNotEmpty()
        }

        // A freshly opened tab is shown at once.
        val selectTab = state.selectTab
        LaunchedEffect(selectTab, pageKeys) {
            val index = selectTab?.let { pageKeys.indexOf(PageKey.Tab(it)) } ?: return@LaunchedEffect
            if (index >= 0) {
                pagerState.scrollToPage(index)
                viewModel.onTabSelected()
            }
        }

        MainScreen(
            tabs = tabs,
            pages = pageKeys,
            pager = pagerState,
            actions = MainScreenActions(
                onSelectPage = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                onCloseTab = viewModel::requestCloseTab,
                onRenameTab = { renaming = it },
                onNewTab = { pickingTab = true },
                onZoom = ::zoom,
                onCloseCurrentTab = { currentTabId()?.let(viewModel::requestCloseTab) },
                onManageConnections = { navigate(Route.Connections) },
                onSync = { navigate(Route.Sync) },
                onLanguage = { pickingLanguage = true },
                onProblemReport = viewModel::shareLatestProblemReport,
                onPrivacyPolicy = ::openPrivacyPolicy,
                onExit = viewModel::requestCloseApp,
            ),
            page = { page ->
                val register: (TerminalView?) -> Unit = { view ->
                    if (view == null) terminals.remove(page) else terminals[page] = view
                }
                when (page) {
                    is PageKey.Tab -> SshTerminalPage(page.id, viewModel, register)
                    else -> StaticTerminalPage(page, register)
                }
            },
            keybar = {
                val modifiers by viewModel.modifiers.state.collectAsStateWithLifecycle()
                Keybar(modifiers, host = this)
            },
        )
        Overlay(state, navigate)
        if (pickingTab) TabPicker(pagerState, scope, onForm = { form = it }, onDismiss = { pickingTab = false })
        form?.let { request -> ConnectionForm(request, onClose = { form = null }) }
    }

    @Composable
    private fun TabPicker(
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

    @Composable
    private fun ConnectionForm(request: FormRequest, onClose: () -> Unit) {
        ConnectionFormDialog(
            request = request,
            onSaved = { id ->
                onClose()
                if (request.openTab) viewModel.openTab(id)
            },
            onDismiss = onClose,
        )
    }

    /**
     * Dialogs and toasts over the screen. A session's question stays pending
     * in the ViewModel, so a recreated activity asks it again.
     */
    @Composable
    private fun Overlay(state: MainViewModel.UiState, navigate: (Route) -> Unit) {
        state.activePrompt?.let { active ->
            key(active.prompt.id) {
                PromptDialog(active.prompt) { answer -> viewModel.answerPrompt(active.tabId, active.prompt.id, answer) }
            }
        }

        state.problemReport?.let { file ->
            ProblemReportDialog(
                onShare = {
                    ProblemReportUi.share(this, file)
                    viewModel.onProblemReportHandled()
                },
                onDismiss = viewModel::onProblemReportHandled,
            )
        }

        Confirmations(state.confirm)
        Messages(state.message, state.connectErrors)
        RestoreOffer(navigate)

        UploadStatus(state.upload, toasts, onShown = viewModel::onUploadShown, onUndo = viewModel::undoUpload)

        renaming?.let { tab ->
            RenameTabDialog(tab.title, onRename = { viewModel.renameTab(tab.id, it) }, onDismiss = { renaming = null })
        }
        if (pickingLanguage) {
            val current = AppCompatDelegate.getApplicationLocales()
            LanguageDialog(
                currentTag = if (current.isEmpty) "" else current[0]?.language.orEmpty(),
                onPick = { tag ->
                    val locales = if (tag.isEmpty()) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                    AppCompatDelegate.setApplicationLocales(locales)
                },
                onDismiss = { pickingLanguage = false },
            )
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ToastHost(toasts, modifier = Modifier.padding(bottom = TOAST_BOTTOM))
        }
    }

    /** On a first start with nothing saved, offer to restore from Google Drive. */
    @Composable
    private fun RestoreOffer(navigate: (Route) -> Unit) {
        val offer by syncViewModel.offerRestore.collectAsStateWithLifecycle()
        if (!offer) return
        AlertDialog(
            onDismissRequest = syncViewModel::dismissOffer,
            title = { Text(stringResource(R.string.sync_offer_title)) },
            text = { Text(stringResource(R.string.sync_offer_text)) },
            confirmButton = {
                TextButton(onClick = {
                    syncViewModel.dismissOffer()
                    navigate(Route.Sync)
                }) { Text(stringResource(R.string.sync_restore)) }
            },
            dismissButton = {
                TextButton(onClick = syncViewModel::dismissOffer) { Text(stringResource(R.string.sync_offer_later)) }
            },
        )
    }

    @Composable
    private fun Confirmations(confirm: MainViewModel.Confirm?) {
        var shown by remember { mutableStateOf<MainViewModel.Confirm?>(null) }
        LaunchedEffect(confirm) {
            confirm?.let {
                shown = it
                viewModel.onConfirmShown()
            }
        }
        shown?.let { question ->
            val text = when (question) {
                is MainViewModel.Confirm.CloseTab -> stringResource(R.string.close_tab_confirm, question.title)
                is MainViewModel.Confirm.CloseApp ->
                    pluralStringResource(R.plurals.close_app_confirm, question.connected, question.connected)
            }
            ConfirmCloseDialog(text, onConfirm = { viewModel.confirmed(question) }, onDismiss = { shown = null })
        }
    }

    // A failed connect closes its tab after the toast unless the user acts first.
    @Composable
    private fun Messages(message: MainViewModel.Message?, errors: List<MainViewModel.ConnectError>) {
        val text = when (val m = message) {
            null -> null
            is MainViewModel.Message.Error -> SshErrorMessages.text(this, m.error)
            MainViewModel.Message.NoProblemReport -> stringResource(R.string.report_none)
            is MainViewModel.Message.UploadUndone -> stringResource(R.string.upload_undone, m.name)
            is MainViewModel.Message.UndoFailed -> stringResource(R.string.upload_undo_failed, m.reason)
        }
        LaunchedEffect(message) {
            if (text != null) {
                toasts.show(text)
                viewModel.onMessageShown()
            }
        }
        val closeTab = stringResource(R.string.close_tab)
        LaunchedEffect(errors) {
            errors.forEach { failed ->
                viewModel.onErrorShown(failed.tabId)
                val text = SshErrorMessages.text(this@MainActivity, failed.error)
                toasts.showAutoAction(text, closeTab, ERROR_TOAST_MS) {
                    viewModel.closeTab(failed.tabId)
                }
            }
        }
    }

    // ── KeybarHost ────────────────────────────────────────────────────────────

    private fun currentPage(): PageKey? = pager?.let { pages.getOrNull(it.currentPage) }

    override fun currentTerminal(): TerminalView? = currentPage()?.let(terminals::get)

    private fun currentTabId(): String? = (currentPage() as? PageKey.Tab)?.id

    override fun sendKey(bytes: ByteArray) {
        val tabId = currentTabId() ?: return
        viewModel.send(tabId, bytes)
        currentTerminal()?.scrollToBottom()
    }

    override fun sendRaw(bytes: ByteArray) {
        val tabId = currentTabId() ?: return
        viewModel.sendRaw(tabId, bytes)
        currentTerminal()?.scrollToBottom()
    }

    override fun toggleModifier(modifier: Key) = viewModel.modifiers.toggle(modifier)

    override fun pickUploadFile() = pickFileForUpload.launch(arrayOf("*/*"))

    override fun toggleKeyboard() {
        val imm = getSystemService<InputMethodManager>() ?: return
        val root = window.decorView
        val imeVisible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (imeVisible) {
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } else {
            currentTerminal()?.focusAndShowKeyboard()
        }
    }

    override fun showMessage(message: String) = toasts.show(message)

    private fun zoom(delta: Float) {
        val terminal = currentTerminal() ?: return
        terminal.zoom(delta)
        viewModel.saveFontSize(terminal.fontSize)
    }

    /** Google Play requires the policy to be reachable from inside the app. */
    private fun openPrivacyPolicy() {
        val page = Intent(Intent.ACTION_VIEW, getString(R.string.privacy_policy_url).toUri())
        try {
            startActivity(page)
        } catch (e: ActivityNotFoundException) {
            toasts.show(getString(R.string.err_no_browser))
        }
    }

    // ── App lifecycle ─────────────────────────────────────────────────────────

    private fun finishApp() {
        SshForegroundService.stop(this)
        finishAffinity()
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    // ── Hardware keys ─────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in SYSTEM_KEYS) {
            val terminal = currentTerminal()
            if (terminal != null && terminal.isAttachedToWindow && terminal.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val ERROR_TOAST_MS = 5000L
        val TOAST_BOTTOM = 100.dp
        val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
        )
    }
}
