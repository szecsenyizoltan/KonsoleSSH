package hu.billman.konsolessh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.ActivityMainBinding
import hu.billman.konsolessh.service.SshForegroundService
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.keybar.Keybar
import hu.billman.konsolessh.ui.keybar.KeybarHost
import hu.billman.konsolessh.ui.theme.KonsoleTheme
import kotlinx.coroutines.launch
import hu.billman.konsolessh.terminal.Modifier as Key

/**
 * A főképernyő: fülsáv, termináloldalak, billentyűsor. Állapotot nem
 * birtokol; a [MainViewModel] állapotát rajzolja ki, és parancsot ad neki.
 */
class MainActivity : AppCompatActivity(), KeybarHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: TerminalPagerAdapter
    private lateinit var uploads: UploadController

    private val viewModel: MainViewModel by viewModels { KonsoleViewModelFactory.from(this) }

    /** A fülsáv utoljára kirajzolt (id, cím) listája; csak változáskor épül újra. */
    private var renderedTabs: List<Pair<String, String>> = emptyList()
    private val tabViews = mutableMapOf<String, View>()
    private var syncingTabs = false

    private val toasts = ToastHostState()

    /** The tab whose rename dialog is open. */
    private var renaming by mutableStateOf<TabState?>(null)
    private var pickingLanguage by mutableStateOf(false)

    private val pickFileForUpload = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) uploads.upload(currentTabId(), uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarAndImePadding()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        requestRuntimePermissionsIfNeeded()

        // With targetSdk 36+ onBackPressed is no longer called. Back moves the
        // app to the background so the sessions stay alive.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        setupViewPager()
        binding.keybar.setContent {
            KonsoleTheme {
                val modifiers by viewModel.modifiers.state.collectAsStateWithLifecycle()
                Keybar(modifiers, host = this)
            }
        }
        uploads = UploadController(this, viewModel)
        binding.overlay.setContent { KonsoleTheme { Overlay() } }

        binding.btnNewTab.setOnClickListener { showTabPicker() }
        binding.btnZoomOut.setOnClickListener { zoom(-1f) }
        binding.btnZoomIn.setOnClickListener { zoom(+1f) }

        listenForDialogResults()
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.sessionsState.collect { renderTabs(it.tabs) } }
                launch { viewModel.selectTab.collect { applyPendingSelection() } }
                // Started from the visible activity, as Android requires; the service stops itself.
                launch {
                    viewModel.serviceNeeded.collect { needed ->
                        if (needed) SshForegroundService.start(this@MainActivity)
                    }
                }
                launch {
                    viewModel.reportToShare.collect { report ->
                        report ?: return@collect
                        ProblemReportUi.share(this@MainActivity, report)
                        viewModel.onReportShared()
                    }
                }
                launch { viewModel.exit.collect { exit -> if (exit) finishApp() } }
            }
        }
    }

    /**
     * Dialogs and toasts over the screen. A session's question stays pending
     * in the ViewModel, so a recreated activity asks it again.
     */
    @Composable
    private fun Overlay() {
        val prompt by viewModel.activePrompt.collectAsStateWithLifecycle()
        prompt?.let { active ->
            key(active.prompt.id) {
                PromptDialog(active.prompt) { answer -> viewModel.answerPrompt(active.tabId, active.prompt.id, answer) }
            }
        }

        val report by viewModel.problemReport.collectAsStateWithLifecycle()
        report?.let { file ->
            ProblemReportDialog(
                onShare = {
                    ProblemReportUi.share(this, file)
                    viewModel.onProblemReportHandled()
                },
                onDismiss = viewModel::onProblemReportHandled,
            )
        }

        Confirmations()
        Messages()

        val upload by viewModel.upload.collectAsStateWithLifecycle()
        UploadStatus(upload, toasts, onShown = viewModel::onUploadShown, onUndo = viewModel::undoUpload)

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

    @Composable
    private fun Confirmations() {
        val confirm by viewModel.confirm.collectAsStateWithLifecycle()
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
                is MainViewModel.Confirm.CloseApp -> stringResource(R.string.close_app_confirm, question.connected)
            }
            ConfirmCloseDialog(text, onConfirm = { viewModel.confirmed(question) }, onDismiss = { shown = null })
        }
    }

    // A failed connect closes its tab after the toast unless the user acts first.
    @Composable
    private fun Messages() {
        val message by viewModel.message.collectAsStateWithLifecycle()
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
        val errors by viewModel.connectErrors.collectAsStateWithLifecycle()
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

    // ── Dialog / sheet results ────────────────────────────────────────────────

    private fun listenForDialogResults() {
        supportFragmentManager.setFragmentResultListener(NewConnectionDialog.RESULT_KEY, this) { _, result ->
            if (result.getBoolean(NewConnectionDialog.KEY_OPEN_TAB)) {
                result.getString(NewConnectionDialog.KEY_CONNECTION_ID)?.let(viewModel::openTab)
            }
        }
        supportFragmentManager.setFragmentResultListener(TabPickerSheet.RESULT_KEY, this) { _, result ->
            val connectionId = result.getString(TabPickerSheet.KEY_CONNECTION_ID)
            when (TabPickerSheet.Action.valueOf(result.getString(TabPickerSheet.KEY_ACTION).orEmpty())) {
                TabPickerSheet.Action.SELECT_TAB ->
                    binding.viewPager.setCurrentItem(result.getInt(TabPickerSheet.KEY_POSITION) + 1, true)
                TabPickerSheet.Action.OPEN_CONNECTION -> connectionId?.let(viewModel::openTab)
                TabPickerSheet.Action.NEW_CONNECTION ->
                    NewConnectionDialog.newForCreate(openTab = true).show(supportFragmentManager, DIALOG_TAG)
                TabPickerSheet.Action.EDIT_CONNECTION -> connectionId?.let {
                    NewConnectionDialog.newForEdit(it).show(supportFragmentManager, DIALOG_TAG)
                }
                TabPickerSheet.Action.CLOSE_APP -> viewModel.requestCloseApp()
            }
        }
    }

    private fun showTabPicker() {
        TabPickerSheet.newInstance(binding.viewPager.currentItem).show(supportFragmentManager, "tab_picker")
    }

    // ── Tabs ──────────────────────────────────────────────────────────────────

    private fun setupViewPager() {
        pagerAdapter = TerminalPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 5 // keeps a few SSH tabs' views alive while swiping
        val indicatorHeight = resources.getDimensionPixelSize(R.dimen.tab_indicator_height)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                syncingTabs = true
                @Suppress("DEPRECATION")
                if (pagerAdapter.isFixedPage(position)) {
                    binding.tabLayout.setSelectedTabIndicatorHeight(0)
                    binding.tabLayout.selectTab(null)
                } else {
                    binding.tabLayout.setSelectedTabIndicatorHeight(indicatorHeight)
                    binding.tabLayout.getTabAt(position - 1)?.select()
                }
                syncingTabs = false
            }
        })
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!syncingTabs) binding.viewPager.setCurrentItem(tab.position + 1, true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun renderTabs(tabs: List<TabState>) {
        pagerAdapter.submit(tabs.map { it.id })
        val shape = tabs.map { it.id to it.title }
        if (shape != renderedTabs) {
            renderedTabs = shape
            rebuildTabStrip(tabs)
        }
        tabs.forEach { tab -> tabViews[tab.id]?.let { applyStatusDot(it, tab.status) } }
        applyPendingSelection()
    }

    private fun rebuildTabStrip(tabs: List<TabState>) {
        syncingTabs = true
        tabViews.clear()
        binding.tabLayout.removeAllTabs()
        tabs.forEach { tab ->
            val view = LayoutInflater.from(this).inflate(R.layout.tab_custom, binding.tabLayout, false)
            view.findViewById<TextView>(R.id.tabTitle).text = tab.title
            view.setOnClickListener { binding.viewPager.setCurrentItem(pagerAdapter.positionOf(tab.id), true) }
            view.findViewById<TextView>(R.id.tabClose).setOnClickListener { viewModel.requestCloseTab(tab.id) }
            view.setOnLongClickListener {
                renaming = tab
                true
            }
            tabViews[tab.id] = view
            binding.tabLayout.addTab(binding.tabLayout.newTab().setCustomView(view), false)
        }
        val position = binding.viewPager.currentItem
        if (!pagerAdapter.isFixedPage(position)) binding.tabLayout.getTabAt(position - 1)?.select()
        syncingTabs = false
    }

    private fun applyPendingSelection() {
        val tabId = viewModel.selectTab.value ?: return
        val position = pagerAdapter.positionOf(tabId)
        if (position < 0) return
        binding.viewPager.setCurrentItem(position, false)
        viewModel.onTabSelected()
    }

    private fun applyStatusDot(tabView: View, status: ConnectionStatus) {
        val dot = tabView.findViewById<TextView>(R.id.tabStatusDot) ?: return
        dot.visibility = if (status == ConnectionStatus.NONE) View.GONE else View.VISIBLE
        statusColor(status)?.let(dot::setTextColor)
    }

    // ── KeybarHost ────────────────────────────────────────────────────────────

    override fun currentTerminal(): TerminalView? = pagerAdapter.pageAt(binding.viewPager.currentItem)?.terminalView

    private fun currentTabId(): String? = pagerAdapter.tabIdAt(binding.viewPager.currentItem)

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
        val imeVisible = ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (imeVisible) {
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
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

    // ── App lifecycle ─────────────────────────────────────────────────────────

    private fun finishApp() {
        SshForegroundService.stop(this)
        finishAffinity()
    }

    // Must be one request: a second concurrent requestPermissions is rejected.
    private fun requestRuntimePermissionsIfNeeded() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    // ── Hardware keys, menu ───────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in SYSTEM_KEYS) {
            val terminal = currentTerminal()
            if (terminal != null && terminal.isAttachedToWindow && terminal.dispatchKeyEvent(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_manage_connections -> {
            startActivity(Intent(this, ConnectionEditActivity::class.java))
            true
        }
        R.id.action_close_tab -> {
            currentTabId()?.let(viewModel::requestCloseTab)
            true
        }
        R.id.action_language -> {
            pickingLanguage = true
            true
        }
        R.id.action_problem_report -> {
            viewModel.shareLatestProblemReport()
            true
        }
        R.id.action_exit -> {
            viewModel.requestCloseApp()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private companion object {
        const val DIALOG_TAG = "connection_dialog"
        const val ERROR_TOAST_MS = 5000L
        val TOAST_BOTTOM = 100.dp
        val SYSTEM_KEYS = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
        )

        fun statusColor(status: ConnectionStatus): Int? = when (status) {
            ConnectionStatus.NONE -> null
            ConnectionStatus.CONNECTING -> Color.rgb(240, 180, 0)
            ConnectionStatus.CONNECTED -> Color.rgb(63, 185, 80)
            ConnectionStatus.DISCONNECTED -> Color.rgb(248, 81, 73)
        }
    }
}
