package hu.billman.konsolessh.ui

import android.Manifest
import android.app.Dialog
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
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.ActivityMainBinding
import hu.billman.konsolessh.service.SshForegroundService
import hu.billman.konsolessh.session.ConnectionStatus
import hu.billman.konsolessh.session.SessionsState
import hu.billman.konsolessh.session.TabState
import hu.billman.konsolessh.terminal.Modifier
import hu.billman.konsolessh.terminal.TerminalView
import hu.billman.konsolessh.ui.keybar.KeybarController
import kotlinx.coroutines.launch
import java.io.File

/**
 * A főképernyő: fülsáv, termináloldalak, billentyűsor. Állapotot nem
 * birtokol; a [MainViewModel] állapotát rajzolja ki, és parancsot ad neki.
 */
class MainActivity : AppCompatActivity(), KeybarController.Host {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: TerminalPagerAdapter
    private lateinit var keybar: KeybarController
    private lateinit var uploads: UploadController

    private val viewModel: MainViewModel by viewModels { KonsoleViewModelFactory.from(this) }

    /** A fülsáv utoljára kirajzolt (id, cím) listája; csak változáskor épül újra. */
    private var renderedTabs: List<Pair<String, String>> = emptyList()
    private val tabViews = mutableMapOf<String, View>()
    private var syncingTabs = false

    private var promptDialog: Dialog? = null
    private var shownPromptId: Long? = null
    private var lastActiveCount = 0
    private var reportDialog: Dialog? = null

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
        keybar = KeybarController(binding, this)
        keybar.restoreState(savedInstanceState)
        uploads = UploadController(this, viewModel, binding.root)

        binding.btnNewTab.setOnClickListener { showTabPicker() }
        binding.btnZoomOut.setOnClickListener { zoom(-1f) }
        binding.btnZoomIn.setOnClickListener { zoom(+1f) }

        listenForDialogResults()
        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        keybar.saveState(outState)
    }

    override fun onDestroy() {
        // The prompt stays pending; the recreated activity shows it again.
        promptDialog?.dismiss()
        reportDialog?.dismiss()
        keybar.dispose()
        super.onDestroy()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.sessionsState.collect(::render) }
                launch { viewModel.selectTab.collect { applyPendingSelection() } }
                launch { viewModel.modifiers.state.collect(keybar::render) }
                launch { viewModel.problemReport.collect(::offerProblemReport) }
                launch {
                    viewModel.storeError.collect { error ->
                        error ?: return@collect
                        KonsoleToast.show(binding.root, SshErrorMessages.text(this@MainActivity, error))
                        viewModel.onStoreErrorShown()
                    }
                }
            }
        }
    }

    private fun offerProblemReport(report: File?) {
        if (report == null || reportDialog != null) return
        reportDialog = ProblemReportUi.offer(this, report) {
            reportDialog = null
            viewModel.onProblemReportHandled()
        }
    }

    private fun shareLatestProblemReport() {
        lifecycleScope.launch {
            val report = viewModel.latestProblemReport()
            if (report == null) {
                KonsoleToast.show(binding.root, getString(R.string.report_none))
            } else {
                ProblemReportUi.share(this@MainActivity, report)
            }
        }
    }

    private fun render(state: SessionsState) {
        // The service runs only while a session needs it and stops itself when
        // none is left; starting it here keeps the start in the foreground.
        if (state.activeCount > 0 && lastActiveCount == 0) SshForegroundService.start(this)
        lastActiveCount = state.activeCount
        renderTabs(state.tabs)
        renderPrompt(state.tabs)
        state.tabs.filter { it.error != null }.forEach(::showConnectError)
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
                TabPickerSheet.Action.CLOSE_APP -> closeApp()
            }
        }
    }

    private fun showTabPicker() {
        TabPickerSheet.newInstance(binding.viewPager.currentItem).show(supportFragmentManager, "tab_picker")
    }

    // ── Prompts and errors ────────────────────────────────────────────────────

    private fun renderPrompt(tabs: List<TabState>) {
        val tab = tabs.firstOrNull { it.prompt != null }
        val prompt = tab?.prompt
        if (prompt?.id == shownPromptId) return
        promptDialog?.dismiss()
        shownPromptId = prompt?.id
        promptDialog = if (tab != null && prompt != null) {
            PromptDialogs.show(this, prompt) { answer -> viewModel.answerPrompt(tab.id, prompt.id, answer) }
        } else {
            null
        }
    }

    // A failed connect closes its tab after the toast unless the user acts first.
    private fun showConnectError(tab: TabState) {
        val error = tab.error ?: return
        viewModel.onErrorShown(tab.id)
        KonsoleToast.showAutoAction(
            anchor = binding.root,
            message = SshErrorMessages.text(this, error),
            actionLabel = getString(R.string.close_tab),
            timeoutMs = ERROR_TOAST_MS,
        ) { viewModel.closeTab(tab.id) }
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
            view.findViewById<TextView>(R.id.tabClose).setOnClickListener { confirmCloseTab(tab.id) }
            view.setOnLongClickListener {
                showRenameDialog(tab)
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

    private fun confirmCloseTab(tabId: String) {
        val tab = viewModel.sessionsState.value.tab(tabId) ?: return
        if (tab.status != ConnectionStatus.CONNECTED) {
            viewModel.closeTab(tabId)
            return
        }
        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setMessage(getString(R.string.close_tab_confirm, tab.title))
            .setPositiveButton(R.string.action_close) { _, _ -> viewModel.closeTab(tabId) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showRenameDialog(tab: TabState) {
        val padding = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        val editText = EditText(this).apply {
            setText(tab.title)
            selectAll()
            hint = getString(R.string.tab_name_hint)
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setTitle(R.string.dialog_rename_tab_title)
            .setView(editText)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                viewModel.renameTab(tab.id, editText.text.toString().trim().ifEmpty { tab.title })
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ── KeybarController.Host ─────────────────────────────────────────────────

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

    override fun toggleModifier(modifier: Modifier) = viewModel.modifiers.toggle(modifier)

    override fun pickUploadFile() = pickFileForUpload.launch(arrayOf("*/*"))

    override fun showMessage(message: String) = KonsoleToast.show(binding.root, message)

    private fun zoom(delta: Float) {
        val terminal = currentTerminal() ?: return
        terminal.zoom(delta)
        viewModel.fontSizeSp = terminal.fontSize
    }

    // ── App lifecycle ─────────────────────────────────────────────────────────

    private fun closeApp() {
        val active = viewModel.sessionsState.value.connectedCount
        if (active == 0) {
            doCloseApp()
            return
        }
        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setMessage(getString(R.string.close_app_confirm, active))
            .setPositiveButton(R.string.action_close) { _, _ -> doCloseApp() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun doCloseApp() {
        viewModel.closeAll()
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
            currentTabId()?.let(::confirmCloseTab)
            true
        }
        R.id.action_language -> {
            showLanguagePicker()
            true
        }
        R.id.action_problem_report -> {
            shareLatestProblemReport()
            true
        }
        R.id.action_exit -> {
            closeApp()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showLanguagePicker() {
        val tags = arrayOf("", "en", "hu", "de", "es", "fr", "sk", "ro")
        val labels = arrayOf(
            getString(R.string.language_system_default),
            "English",
            "Magyar",
            "Deutsch",
            "Español",
            "Français",
            "Slovenčina",
            "Română",
        )
        val current = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (current.isEmpty) "" else current[0]?.language.orEmpty()
        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setTitle(R.string.action_language)
            .setSingleChoiceItems(labels, tags.indexOf(currentTag).coerceAtLeast(0)) { dialog, which ->
                val locales = if (tags[which].isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tags[which])
                }
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private companion object {
        const val DIALOG_TAG = "connection_dialog"
        const val ERROR_TOAST_MS = 5000L
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
