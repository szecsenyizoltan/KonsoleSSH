package hu.szecsenyi.konsolessh.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.viewpager2.widget.ViewPager2
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.material.tabs.TabLayout
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.ActivityMainBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.ssh.SshForegroundService
import hu.szecsenyi.konsolessh.terminal.AppClipboard

class MainActivity : AppCompatActivity(), TabStatusListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: TerminalPagerAdapter

    private val tabStatusMap = mutableMapOf<String, ConnectionStatus>()
    private val tabViewMap   = mutableMapOf<String, View>()
    private var syncingTabs  = false

    // ── Service binding ───────────────────────────────────────────────────────

    private var sshService: SshForegroundService? = null
    private var serviceBound = false

    private val mainServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sshService = (binder as SshForegroundService.LocalBinder).getService()
            serviceBound = true
            restoreTabsFromService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sshService = null
            serviceBound = false
        }
    }

    private fun restoreTabsFromService() {
        val service = sshService ?: return
        if (service.tabs.isNotEmpty() && pagerAdapter.getTabCount() == 0) {
            service.tabs.forEach { pagerAdapter.addTab(it) }
            rebuildTabs()
            binding.viewPager.setCurrentItem(1, false)
        }
    }

    // ── Modifier state ────────────────────────────────────────────────────────

    private var modCtrl  = false
    private var modShift = false
    private var modAlt   = false
    private var modAltGr = false
    private var keyboardVisible = false

    private var keybarHintAnimRight: ObjectAnimator? = null
    private var keybarHintAnimLeft:  ObjectAnimator? = null
    private var fnbarHintAnimRight:  ObjectAnimator? = null
    private var fnbarHintAnimLeft:   ObjectAnimator? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        requestNotificationPermissionIfNeeded()

        setupViewPager()
        setupKeybar()
        binding.btnNewTab.setOnClickListener { showTabPicker() }
        binding.btnZoomOut.setOnClickListener { currentTerminalView()?.let { it.zoom(-1f); saveZoom(it.fontSize) } }
        binding.btnZoomIn.setOnClickListener  { currentTerminalView()?.let { it.zoom(+1f); saveZoom(it.fontSize) } }
    }

    override fun onStart() {
        super.onStart()
        SshForegroundService.start(this)
        bindService(
            Intent(this, SshForegroundService::class.java),
            mainServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(mainServiceConnection)
            serviceBound = false
            sshService = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        keybarHintAnimRight?.cancel(); keybarHintAnimLeft?.cancel()
        fnbarHintAnimRight?.cancel();  fnbarHintAnimLeft?.cancel()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun closeApp() {
        val activeCount = tabStatusMap.values.count { it == ConnectionStatus.CONNECTED }
        if (activeCount > 0) {
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.KonsoleDialog)
                .setMessage("$activeCount aktív kapcsolat van nyitva. Biztosan bezárod az alkalmazást?")
                .setPositiveButton("Bezárás") { _, _ -> doCloseApp() }
                .setNegativeButton("Mégse", null)
                .show()
        } else {
            doCloseApp()
        }
    }

    private fun doCloseApp() {
        sshService?.clearTabs()
        stopService(Intent(this, SshForegroundService::class.java))
        finishAffinity()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun currentTerminalView() =
        pagerAdapter.getFragment(binding.viewPager.currentItem)?.terminalView

    private fun currentFragment(): TerminalFragment? =
        pagerAdapter.getFragment(binding.viewPager.currentItem)

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun saveZoom(sp: Float) {
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putFloat("font_size", sp).apply()
    }

    companion object {
        fun savedFontSize(context: Context): Float =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getFloat("font_size", 0f)
    }

    // ── TabStatusListener ─────────────────────────────────────────────────────

    override fun onTabStatusChanged(tabId: String, status: ConnectionStatus) {
        tabStatusMap[tabId] = status
        tabViewMap[tabId]?.let { applyStatusDot(it, status) }
    }

    private fun applyStatusDot(tabView: View, status: ConnectionStatus) {
        val dot = tabView.findViewById<TextView>(R.id.tabStatusDot) ?: return
        when (status) {
            ConnectionStatus.NONE         -> dot.visibility = View.GONE
            ConnectionStatus.CONNECTING   -> { dot.visibility = View.VISIBLE; dot.setTextColor(Color.rgb(240, 180, 0)) }
            ConnectionStatus.CONNECTED    -> { dot.visibility = View.VISIBLE; dot.setTextColor(Color.rgb(63, 185, 80)) }
            ConnectionStatus.DISCONNECTED -> { dot.visibility = View.VISIBLE; dot.setTextColor(Color.rgb(248, 81, 73)) }
        }
    }

    // ── Keybar ────────────────────────────────────────────────────────────────

    private fun setupKeybar() {
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }

        binding.btnModCtrl.setOnClickListener  { toggleMod(binding.btnModCtrl,  ::modCtrl) }
        binding.btnModShift.setOnClickListener { toggleMod(binding.btnModShift, ::modShift) }
        binding.btnModAlt.setOnClickListener   { toggleMod(binding.btnModAlt,   ::modAlt) }
        binding.btnModAltGr.setOnClickListener { toggleMod(binding.btnModAltGr, ::modAltGr) }

        binding.btnEscape.setOnClickListener   { flashButton(binding.btnEscape);   sendKey(byteArrayOf(27)) }
        binding.btnTab.setOnClickListener      { flashButton(binding.btnTab);      sendKey(byteArrayOf(9)) }
        binding.btnCtrlC.setOnClickListener {
            flashButton(binding.btnCtrlC)
            val sel = currentFragment()?.getSelectedText() ?: ""
            if (sel.isNotEmpty()) {
                AppClipboard.text = sel
                KonsoleToast.show(binding.root, "Másolva")
            } else {
                sendKey(byteArrayOf(3))
            }
        }
        binding.btnCtrlD.setOnClickListener { flashButton(binding.btnCtrlD); sendKey(byteArrayOf(4)) }
        binding.btnCtrlV.setOnClickListener {
            flashButton(binding.btnCtrlV)
            val clip = AppClipboard.text
            if (!clip.isNullOrEmpty()) {
                currentFragment()?.pasteText(clip)
                KonsoleToast.show(binding.root, "Beillesztve")
            }
        }
        binding.btnCtrlZ.setOnClickListener { flashButton(binding.btnCtrlZ); sendKey(byteArrayOf(26)) }

        binding.btnArrowUp.setOnClickListener    { flashButton(binding.btnArrowUp);    sendKey(currentFragment()?.cursorKeyBytes('A') ?: byteArrayOf()) }
        binding.btnArrowDown.setOnClickListener  { flashButton(binding.btnArrowDown);  sendKey(currentFragment()?.cursorKeyBytes('B') ?: byteArrayOf()) }
        binding.btnArrowLeft.setOnClickListener  { flashButton(binding.btnArrowLeft);  sendKey(currentFragment()?.cursorKeyBytes('D') ?: byteArrayOf()) }
        binding.btnArrowRight.setOnClickListener { flashButton(binding.btnArrowRight); sendKey(currentFragment()?.cursorKeyBytes('C') ?: byteArrayOf()) }

        binding.btnF1.setOnClickListener  { flashButton(binding.btnF1);  sendKey(fnKey("OP")) }
        binding.btnF2.setOnClickListener  { flashButton(binding.btnF2);  sendKey(fnKey("OQ")) }
        binding.btnF3.setOnClickListener  { flashButton(binding.btnF3);  sendKey(fnKey("OR")) }
        binding.btnF4.setOnClickListener  { flashButton(binding.btnF4);  sendKey(fnKey("OS")) }
        binding.btnF5.setOnClickListener  { flashButton(binding.btnF5);  sendKey(fnKey("[15~")) }
        binding.btnF6.setOnClickListener  { flashButton(binding.btnF6);  sendKey(fnKey("[17~")) }
        binding.btnF7.setOnClickListener  { flashButton(binding.btnF7);  sendKey(fnKey("[18~")) }
        binding.btnF8.setOnClickListener  { flashButton(binding.btnF8);  sendKey(fnKey("[19~")) }
        binding.btnF9.setOnClickListener  { flashButton(binding.btnF9);  sendKey(fnKey("[20~")) }
        binding.btnF10.setOnClickListener { flashButton(binding.btnF10); sendKey(fnKey("[21~")) }
        binding.btnF11.setOnClickListener { flashButton(binding.btnF11); sendKey(fnKey("[23~")) }
        binding.btnF12.setOnClickListener { flashButton(binding.btnF12); sendKey(fnKey("[24~")) }

        setupKeybarScrollHint()
        setupFnbarScrollHint()
    }

    private fun sendKey(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        currentFragment()?.sendBytes(applyModifiers(bytes))
        resetModifiers()
        currentFragment()?.focusTerminal()
    }

    private fun toggleKeyboard() {
        val imm = getSystemService<InputMethodManager>() ?: return
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            keyboardVisible = false
        } else {
            currentFragment()?.focusTerminal()
            keyboardVisible = true
        }
    }

    fun applyModifiers(bytes: ByteArray): ByteArray {
        if (!modCtrl && !modShift && !modAlt && !modAltGr) return bytes
        var result = bytes
        if (modCtrl && result.size == 1) {
            val b = result[0].toInt() and 0xFF
            result = when (b) {
                in 'a'.code..'z'.code -> byteArrayOf((b - 'a'.code + 1).toByte())
                in 'A'.code..'Z'.code -> byteArrayOf((b - 'A'.code + 1).toByte())
                ' '.code              -> byteArrayOf(0)
                '['.code              -> byteArrayOf(27)
                else                  -> result
            }
        }
        if (modShift && result.size == 1) {
            val b = result[0].toInt() and 0xFF
            if (b in 'a'.code..'z'.code) result = byteArrayOf((b - 32).toByte())
        }
        if (modAlt || modAltGr) result = byteArrayOf(27) + result
        return result
    }

    fun resetModifiers() {
        if (modCtrl)  { modCtrl  = false; updateModButton(binding.btnModCtrl,  false) }
        if (modShift) { modShift = false; updateModButton(binding.btnModShift, false) }
        if (modAlt)   { modAlt   = false; updateModButton(binding.btnModAlt,   false) }
        if (modAltGr) { modAltGr = false; updateModButton(binding.btnModAltGr, false) }
    }

    private fun toggleMod(button: Button, prop: kotlin.reflect.KMutableProperty0<Boolean>) {
        prop.set(!prop.get())
        updateModButton(button, prop.get())
        currentFragment()?.focusTerminal()
    }

    private fun updateModButton(button: Button, active: Boolean) {
        button.setBackgroundColor(
            if (active) ContextCompat.getColor(this, R.color.keybar_mod_active) else Color.TRANSPARENT
        )
    }

    private fun flashButton(button: Button) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        button.setTextColor(accent)
        button.postDelayed({
            button.setTextColor(ContextCompat.getColor(this, R.color.keybar_key_text))
        }, 300)
    }

    private fun fnKey(seq: String) = byteArrayOf(27) + seq.toByteArray(Charsets.US_ASCII)

    private fun setupKeybarScrollHint() {
        val sv = binding.keybarScrollView
        val hr = binding.keybarScrollHint
        val hl = binding.keybarScrollHintLeft
        val interp = android.view.animation.AccelerateDecelerateInterpolator()
        keybarHintAnimRight = ObjectAnimator.ofFloat(hr, "translationX", 0f, -8f).apply { duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; interpolator = interp }
        keybarHintAnimLeft  = ObjectAnimator.ofFloat(hl, "translationX", 0f,  8f).apply { duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; interpolator = interp }
        fun update() {
            val cr = sv.canScrollHorizontally(1); val cl = sv.canScrollHorizontally(-1)
            if (cr && hr.visibility != View.VISIBLE)  { hr.visibility = View.VISIBLE;  keybarHintAnimRight?.start() }
            else if (!cr && hr.visibility == View.VISIBLE) { hr.visibility = View.GONE; keybarHintAnimRight?.cancel(); hr.translationX = 0f }
            if (cl && hl.visibility != View.VISIBLE)  { hl.visibility = View.VISIBLE;  keybarHintAnimLeft?.start() }
            else if (!cl && hl.visibility == View.VISIBLE) { hl.visibility = View.GONE; keybarHintAnimLeft?.cancel(); hl.translationX = 0f }
        }
        hr.setOnClickListener { sv.smoothScrollBy(sv.width / 2, 0) }
        hl.setOnClickListener { sv.smoothScrollBy(-(sv.width / 2), 0) }
        sv.viewTreeObserver.addOnGlobalLayoutListener { update() }
        sv.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    }

    private fun setupFnbarScrollHint() {
        val sv = binding.fnbarScrollView
        val hr = binding.fnbarScrollHint
        val hl = binding.fnbarScrollHintLeft
        val interp = android.view.animation.AccelerateDecelerateInterpolator()
        fnbarHintAnimRight = ObjectAnimator.ofFloat(hr, "translationX", 0f, -8f).apply { duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; interpolator = interp }
        fnbarHintAnimLeft  = ObjectAnimator.ofFloat(hl, "translationX", 0f,  8f).apply { duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; interpolator = interp }
        fun update() {
            val cr = sv.canScrollHorizontally(1); val cl = sv.canScrollHorizontally(-1)
            if (cr && hr.visibility != View.VISIBLE)  { hr.visibility = View.VISIBLE;  fnbarHintAnimRight?.start() }
            else if (!cr && hr.visibility == View.VISIBLE) { hr.visibility = View.GONE; fnbarHintAnimRight?.cancel(); hr.translationX = 0f }
            if (cl && hl.visibility != View.VISIBLE)  { hl.visibility = View.VISIBLE;  fnbarHintAnimLeft?.start() }
            else if (!cl && hl.visibility == View.VISIBLE) { hl.visibility = View.GONE; fnbarHintAnimLeft?.cancel(); hl.translationX = 0f }
        }
        hr.setOnClickListener { sv.smoothScrollBy(sv.width / 2, 0) }
        hl.setOnClickListener { sv.smoothScrollBy(-(sv.width / 2), 0) }
        sv.viewTreeObserver.addOnGlobalLayoutListener { update() }
        sv.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    }

    // ── ViewPager ─────────────────────────────────────────────────────────────

    private fun setupViewPager() {
        pagerAdapter = TerminalPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 5
        binding.viewPager.isUserInputEnabled = true

        val tabIndicatorHeight = resources.getDimensionPixelSize(R.dimen.tab_indicator_height)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (pagerAdapter.isFixedPage(position)) {
                    binding.tabLayout.setSelectedTabIndicatorHeight(0)
                    syncingTabs = true; binding.tabLayout.selectTab(null); syncingTabs = false
                } else {
                    binding.tabLayout.setSelectedTabIndicatorHeight(tabIndicatorHeight)
                    syncingTabs = true; binding.tabLayout.getTabAt(position - 1)?.select(); syncingTabs = false
                }
            }
        })

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!syncingTabs) binding.viewPager.setCurrentItem(tab.position + 1, true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        rebuildTabs()
    }

    // ── Tab strip ─────────────────────────────────────────────────────────────

    private fun rebuildTabs() {
        tabViewMap.clear()
        binding.tabLayout.removeAllTabs()
        pagerAdapter.getAllTabs().forEachIndexed { internalIdx, info ->
            val tab = binding.tabLayout.newTab()
            tab.customView = buildTabView(info, internalIdx)
            binding.tabLayout.addTab(tab, false)
        }
        val viewPos = binding.viewPager.currentItem
        if (!pagerAdapter.isFixedPage(viewPos)) binding.tabLayout.getTabAt(viewPos - 1)?.select()
    }

    private fun buildTabView(info: TabInfo, internalIdx: Int): View {
        val view = LayoutInflater.from(this).inflate(R.layout.tab_custom, null)
        view.findViewById<TextView>(R.id.tabTitle).text = info.title
        applyStatusDot(view, tabStatusMap[info.id] ?: ConnectionStatus.NONE)
        tabViewMap[info.id] = view
        view.setOnClickListener { binding.viewPager.setCurrentItem(internalIdx + 1, true) }
        view.findViewById<TextView>(R.id.tabClose).setOnClickListener { confirmCloseTab(internalIdx, info.title) }
        view.setOnLongClickListener { showRenameDialog(internalIdx, info.title); true }
        return view
    }

    // ── Confirm / rename ──────────────────────────────────────────────────────

    private fun confirmCloseTab(internalIdx: Int, title: String) {
        val status = tabStatusMap[pagerAdapter.getTab(internalIdx)?.id] ?: ConnectionStatus.NONE
        if (status == ConnectionStatus.CONNECTED) {
            AlertDialog.Builder(this, R.style.KonsoleDialog)
                .setMessage("Bezárod a '$title' fület?")
                .setPositiveButton("Bezárás") { _, _ -> closeTab(internalIdx) }
                .setNegativeButton("Mégse", null).show()
        } else {
            closeTab(internalIdx)
        }
    }

    private fun showRenameDialog(internalIdx: Int, currentTitle: String) {
        val editText = EditText(this).apply {
            setText(currentTitle); selectAll()
            hint = "Fül neve / komment"
            setBackgroundColor(Color.WHITE); setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
        }
        val p = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        editText.setPadding(p, p / 2, p, p / 2)
        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setTitle("Fül átnevezése").setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newTitle = editText.text.toString().trim().ifEmpty { currentTitle }
                pagerAdapter.renameTab(internalIdx, newTitle)
                sshService?.renameTab(pagerAdapter.getTab(internalIdx)?.id ?: "", newTitle)
                rebuildTabs()
            }
            .setNegativeButton("Mégse", null).show()
    }

    // ── Tab picker ────────────────────────────────────────────────────────────

    private fun showTabPicker() {
        val currentViewPos = binding.viewPager.currentItem
        val sheet = TabPickerSheet()
        sheet.openTabs = pagerAdapter.getAllTabs().mapIndexed { idx, tab ->
            TabPickerSheet.TabEntry(
                title = tab.title, isActive = (idx + 1) == currentViewPos,
                host = tab.config?.host?.takeIf { it.isNotBlank() },
                status = tabStatusMap[tab.id] ?: ConnectionStatus.NONE
            )
        }
        sheet.listener = object : TabPickerSheet.Listener {
            override fun onTabSelected(position: Int) { binding.viewPager.setCurrentItem(position + 1, true) }
            override fun onSavedConnectionSelected(config: ConnectionConfig) { openTab(config) }
            override fun onNewConnectionRequested() { showNewConnectionDialog() }
            override fun onEditConnectionRequested(config: ConnectionConfig) { showEditConnectionDialog(config) }
            override fun onDeleteConnectionRequested(config: ConnectionConfig) {}
            override fun onCloseAppRequested() { closeApp() }
        }
        sheet.show(supportFragmentManager, "tab_picker")
    }

    // ── Tab management ────────────────────────────────────────────────────────

    private fun openTab(config: ConnectionConfig?, title: String? = null) {
        val tabTitle = title ?: config?.displayName() ?: "Terminal"
        val tabInfo = TabInfo(config = config, title = tabTitle)
        sshService?.addTab(tabInfo)
        pagerAdapter.addTab(tabInfo)
        rebuildTabs()
        binding.viewPager.setCurrentItem(pagerAdapter.getTabCount(), false)
    }

    private fun closeTab(internalIdx: Int) {
        val tab = pagerAdapter.getTab(internalIdx) ?: return
        sshService?.disconnectSession(tab.id)
        sshService?.removeTab(tab.id)
        pagerAdapter.removeTab(internalIdx)
        tabViewMap.remove(tab.id); tabStatusMap.remove(tab.id)
        rebuildTabs()
        binding.viewPager.setCurrentItem(
            if (pagerAdapter.getTabCount() > 0) if (internalIdx > 0) internalIdx else 1 else 0,
            false
        )
    }

    private fun showNewConnectionDialog() {
        val dialog = NewConnectionDialog()
        dialog.listener = object : NewConnectionDialog.Listener {
            override fun onConnectionSelected(config: ConnectionConfig) { openTab(config) }
        }
        dialog.show(supportFragmentManager, "new_connection")
    }

    private fun showEditConnectionDialog(config: ConnectionConfig) {
        val dialog = NewConnectionDialog.newForEdit(config)
        dialog.listener = object : NewConnectionDialog.Listener {
            override fun onConnectionSelected(config: ConnectionConfig) {}
        }
        dialog.show(supportFragmentManager, "edit_connection")
    }

    // ── Key interception ──────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK &&
            event.keyCode != KeyEvent.KEYCODE_HOME &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            val terminal = currentTerminalView()
            if (terminal != null && terminal.isAttachedToWindow) {
                if (terminal.dispatchKeyEvent(event)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_manage_connections -> {
            startActivity(Intent(this, ConnectionEditActivity::class.java)); true
        }
        R.id.action_close_tab -> {
            val viewPos = binding.viewPager.currentItem
            if (!pagerAdapter.isFixedPage(viewPos)) {
                val internalIdx = viewPos - 1
                val title = pagerAdapter.getTab(internalIdx)?.title ?: "fül"
                confirmCloseTab(internalIdx, title)
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
