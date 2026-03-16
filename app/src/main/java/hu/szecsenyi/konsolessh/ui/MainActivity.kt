package hu.szecsenyi.konsolessh.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.ActivityMainBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig

class MainActivity : AppCompatActivity(), TabStatusListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: TerminalPagerAdapter

    private val tabStatusMap = mutableMapOf<String, ConnectionStatus>()
    private val tabViewMap   = mutableMapOf<String, View>()

    // Guards against feedback loop between page change and tab selection
    private var syncingTabs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        requestNotificationPermissionIfNeeded()

        setupViewPager()
        binding.btnNewTab.setOnClickListener { showTabPicker() }
        binding.btnZoomOut.setOnClickListener { currentTerminalView()?.let { it.zoom(-1f); saveZoom(it.fontSize) } }
        binding.btnZoomIn.setOnClickListener  { currentTerminalView()?.let { it.zoom(+1f); saveZoom(it.fontSize) } }
    }

    private fun currentTerminalView() =
        pagerAdapter.getFragment(binding.viewPager.currentItem)?.terminalView

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
                .getFloat("font_size", 0f)   // 0 = auto (nincs mentve)
    }

    // ── TabStatusListener ─────────────────────────────────────────────────────

    override fun onTabStatusChanged(tabId: String, status: ConnectionStatus) {
        tabStatusMap[tabId] = status
        tabViewMap[tabId]?.let { applyStatusDot(it, status) }
    }

    private fun applyStatusDot(tabView: View, status: ConnectionStatus) {
        val dot = tabView.findViewById<TextView>(R.id.tabStatusDot) ?: return
        when (status) {
            ConnectionStatus.NONE        -> dot.visibility = View.GONE
            ConnectionStatus.CONNECTING  -> { dot.visibility = View.VISIBLE; dot.setTextColor(Color.rgb(240, 180, 0)) }
            ConnectionStatus.CONNECTED   -> { dot.visibility = View.VISIBLE; dot.setTextColor(Color.rgb(63, 185, 80)) }
            ConnectionStatus.DISCONNECTED-> { dot.visibility = View.VISIBLE; dot.setTextColor(Color.rgb(248, 81, 73)) }
        }
    }

    // ── ViewPager setup ───────────────────────────────────────────────────────

    private fun setupViewPager() {
        pagerAdapter = TerminalPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 5
        binding.viewPager.isUserInputEnabled = true

        val tabIndicatorHeight = resources.getDimensionPixelSize(R.dimen.tab_indicator_height)

        // Sync ViewPager → TabLayout selection
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (pagerAdapter.isFixedPage(position)) {
                    binding.tabLayout.setSelectedTabIndicatorHeight(0)
                    syncingTabs = true
                    binding.tabLayout.selectTab(null)
                    syncingTabs = false
                } else {
                    binding.tabLayout.setSelectedTabIndicatorHeight(tabIndicatorHeight)
                    syncingTabs = true
                    binding.tabLayout.getTabAt(position - 1)?.select()
                    syncingTabs = false
                }
            }
        })

        // Sync TabLayout selection → ViewPager
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!syncingTabs) binding.viewPager.setCurrentItem(tab.position + 1, true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        rebuildTabs()
    }

    // ── Tab strip (only user SSH tabs, no fixed pages) ────────────────────────

    private fun rebuildTabs() {
        tabViewMap.clear()
        binding.tabLayout.removeAllTabs()
        pagerAdapter.getAllTabs().forEachIndexed { internalIdx, info ->
            val tab = binding.tabLayout.newTab()
            tab.customView = buildTabView(info, internalIdx)
            binding.tabLayout.addTab(tab, false)
        }
        // Restore selection
        val viewPos = binding.viewPager.currentItem
        if (!pagerAdapter.isFixedPage(viewPos)) {
            binding.tabLayout.getTabAt(viewPos - 1)?.select()
        }
    }

    private fun buildTabView(info: TabInfo, internalIdx: Int): View {
        val view = LayoutInflater.from(this).inflate(R.layout.tab_custom, null)
        view.findViewById<TextView>(R.id.tabTitle).text = info.title
        applyStatusDot(view, tabStatusMap[info.id] ?: ConnectionStatus.NONE)
        tabViewMap[info.id] = view

        view.findViewById<TextView>(R.id.tabClose).setOnClickListener {
            confirmCloseTab(internalIdx, info.title)
        }
        view.setOnLongClickListener {
            showRenameDialog(internalIdx, info.title)
            true
        }
        return view
    }

    // ── Confirm / rename ──────────────────────────────────────────────────────

    private fun confirmCloseTab(internalIdx: Int, title: String) {
        val status = tabStatusMap[pagerAdapter.getTab(internalIdx)?.id] ?: ConnectionStatus.NONE
        if (status == ConnectionStatus.CONNECTED) {
            AlertDialog.Builder(this, R.style.KonsoleDialog)
                .setMessage("Bezárod a '$title' fület?")
                .setPositiveButton("Bezárás") { _, _ -> closeTab(internalIdx) }
                .setNegativeButton("Mégse", null)
                .show()
        } else {
            closeTab(internalIdx)
        }
    }

    private fun showRenameDialog(internalIdx: Int, currentTitle: String) {
        val editText = EditText(this).apply {
            setText(currentTitle)
            selectAll()
            hint = "Fül neve / komment"
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        val p = resources.getDimensionPixelSize(R.dimen.dialog_padding)
        editText.setPadding(p, p / 2, p, p / 2)

        AlertDialog.Builder(this, R.style.KonsoleDialog)
            .setTitle("Fül átnevezése")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newTitle = editText.text.toString().trim().ifEmpty { currentTitle }
                pagerAdapter.renameTab(internalIdx, newTitle)
                rebuildTabs()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    // ── Tab picker ────────────────────────────────────────────────────────────

    private fun showTabPicker() {
        val currentViewPos = binding.viewPager.currentItem
        val sheet = TabPickerSheet()
        sheet.openTabs = pagerAdapter.getAllTabs().mapIndexed { idx, tab ->
            TabPickerSheet.TabEntry(
                title = tab.title,
                isActive = (idx + 1) == currentViewPos,
                host = tab.config?.host?.takeIf { it.isNotBlank() },
                status = tabStatusMap[tab.id] ?: ConnectionStatus.NONE
            )
        }
        sheet.listener = object : TabPickerSheet.Listener {
            override fun onTabSelected(position: Int) {
                binding.viewPager.setCurrentItem(position + 1, true)
            }
            override fun onSavedConnectionSelected(config: ConnectionConfig) { openTab(config) }
            override fun onNewConnectionRequested() { showNewConnectionDialog() }
            override fun onEditConnectionRequested(config: ConnectionConfig) { showEditConnectionDialog(config) }
            override fun onDeleteConnectionRequested(config: ConnectionConfig) {}
        }
        sheet.show(supportFragmentManager, "tab_picker")
    }

    // ── Tab management ────────────────────────────────────────────────────────

    private fun openTab(config: ConnectionConfig?, title: String? = null) {
        val tabTitle = title ?: config?.displayName() ?: "Terminal"
        pagerAdapter.addTab(TabInfo(config = config, title = tabTitle))
        rebuildTabs()
        binding.viewPager.setCurrentItem(pagerAdapter.getTabCount(), false)
    }

    private fun closeTab(internalIdx: Int) {
        val removedId = pagerAdapter.getTab(internalIdx)?.id
        pagerAdapter.removeTab(internalIdx)
        removedId?.let { tabViewMap.remove(it); tabStatusMap.remove(it) }
        rebuildTabs()
        if (pagerAdapter.getTabCount() > 0) {
            binding.viewPager.setCurrentItem(if (internalIdx > 0) internalIdx else 1, false)
        } else {
            binding.viewPager.setCurrentItem(0, false)
        }
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
            startActivity(Intent(this, ConnectionEditActivity::class.java))
            true
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
