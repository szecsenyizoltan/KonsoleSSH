package hu.szecsenyi.konsolessh.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.FragmentTerminalBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections
import hu.szecsenyi.konsolessh.ssh.SshForegroundService

enum class ConnectionStatus { NONE, CONNECTING, CONNECTED, DISCONNECTED }

interface TabStatusListener {
    fun onTabStatusChanged(tabId: String, status: ConnectionStatus)
}

class TerminalFragment : Fragment() {

    companion object {
        private const val ARG_CONFIG_JSON = "config_json"
        private const val ARG_TAB_ID = "tab_id"
        private const val ARG_MODE = "mode"
        const val MODE_CHEAT = "cheatsheet"
        const val MODE_TMUX  = "tmuxsheet"

        fun newInstance(config: ConnectionConfig, tabId: String): TerminalFragment {
            val gson = com.google.gson.Gson()
            return TerminalFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONFIG_JSON, gson.toJson(config))
                    putString(ARG_TAB_ID, tabId)
                }
            }
        }

        fun newWelcome(tabId: String): TerminalFragment =
            TerminalFragment().apply {
                arguments = Bundle().apply { putString(ARG_TAB_ID, tabId) }
            }

        fun newCheatSheet(tabId: String): TerminalFragment =
            TerminalFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_ID, tabId)
                    putString(ARG_MODE, MODE_CHEAT)
                }
            }

        fun newTmuxSheet(tabId: String): TerminalFragment =
            TerminalFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_ID, tabId)
                    putString(ARG_MODE, MODE_TMUX)
                }
            }
    }

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    val terminalView get() = _binding?.terminalView

    private var config: ConnectionConfig? = null
    var tabId: String = ""; private set

    private var statusListener: TabStatusListener? = null

    // ── Service binding ───────────────────────────────────────────────────────

    private var sshService: SshForegroundService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sshService = (binder as SshForegroundService.LocalBinder).getService()
            serviceBound = true
            onServiceBound()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sshService = null
            serviceBound = false
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        statusListener = context as? TabStatusListener
    }

    override fun onDetach() {
        super.onDetach()
        statusListener = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabId = arguments?.getString(ARG_TAB_ID) ?: ""
        val json = arguments?.getString(ARG_CONFIG_JSON) ?: return
        config = com.google.gson.Gson().fromJson(json, ConnectionConfig::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.terminalView.onKeyInput = { bytes ->
            val main = activity as? MainActivity
            sshService?.sendBytes(tabId, main?.applyModifiers(bytes) ?: bytes)
            main?.resetModifiers()
        }

        val mode = arguments?.getString(ARG_MODE)
        binding.terminalView.onTerminalResize = { cols, rows ->
            when (mode) {
                MODE_CHEAT -> { binding.terminalView.clear(); showCheatSheet() }
                MODE_TMUX  -> { binding.terminalView.clear(); showTmuxSheet() }
                else       -> sshService?.resize(tabId, cols, rows)
            }
        }

        binding.btnReconnect.setOnClickListener { reconnect() }

        val cfg = config
        when {
            cfg == null && mode == MODE_CHEAT -> showCheatSheet()
            cfg == null && mode == MODE_TMUX  -> showTmuxSheet()
            cfg == null -> showLocalShellPrompt()
            else -> { /* connection started in onServiceBound */ }
        }
    }

    override fun onStart() {
        super.onStart()
        SshForegroundService.start(requireContext())
        requireContext().bindService(
            Intent(requireContext(), SshForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            sshService?.setDataListener(tabId, null)
            sshService?.setStatusListener(tabId, null)
            sshService?.setPasswordPrompter(tabId, null)
            requireContext().unbindService(serviceConnection)
            serviceBound = false
            sshService = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT disconnect — service owns the session and keeps it alive
    }

    // ── Service bound ─────────────────────────────────────────────────────────

    private fun onServiceBound() {
        val service = sshService ?: return
        val cfg = config ?: return  // no SSH on welcome/cheatsheet tabs

        val savedSp = MainActivity.savedFontSize(requireContext())
        if (savedSp > 0f) _binding?.terminalView?.post { _binding?.terminalView?.setFontSize(savedSp) }

        service.setPasswordPrompter(tabId, buildPasswordPrompter())

        when (service.getStatus(tabId)) {
            ConnectionStatus.NONE -> {
                // First time — start connection (setStatusListener after connect() so new SessionState exists)
                startConnection(cfg, service)
                service.setStatusListener(tabId) { status -> updateStatusUI(status) }
            }
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTED -> {
                // Existing session — register listeners and replay buffer
                service.setStatusListener(tabId) { status -> updateStatusUI(status) }
                replayBuffer(service)
                service.setDataListener(tabId) { bytes ->
                    _binding?.terminalView?.append(bytes, bytes.size)
                }
                updateStatusUI(service.getStatus(tabId))
            }
        }
    }

    private fun replayBuffer(service: SshForegroundService) {
        val buf = service.getBuffer(tabId)
        if (buf.isNotEmpty()) {
            _binding?.terminalView?.post {
                _binding?.terminalView?.append(buf, buf.size)
            }
        }
    }

    private fun startConnection(cfg: ConnectionConfig, service: SshForegroundService) {
        val jumpConfig = resolveJumpConfig(cfg)
        if (cfg.jumpConnectionId.isNotBlank() && jumpConfig == null) {
            val msg = "Hiba: a jump kapcsolat nem található a mentett kapcsolatok között.\r\n"
            _binding?.terminalView?.append(msg.toByteArray(), msg.length)
            updateStatusUI(ConnectionStatus.DISCONNECTED)
            return
        }
        val cols = binding.terminalView.currentCols
        val rows = binding.terminalView.currentRows
        service.connect(tabId, cfg, jumpConfig, cols, rows)
        service.setDataListener(tabId) { bytes ->
            _binding?.terminalView?.append(bytes, bytes.size)
        }
        updateStatusUI(ConnectionStatus.CONNECTING)
        binding.statusBar.text = "Csatlakozás: ${cfg.displayName()}..."
    }

    private fun reconnect() {
        val cfg = config ?: return
        val service = sshService ?: return
        _binding?.btnReconnect?.visibility = View.GONE
        val msg = "\r\n[Újracsatlakozás...]\r\n"
        _binding?.terminalView?.append(msg.toByteArray(), msg.length)
        startConnection(cfg, service)
    }

    // ── Public interface for MainActivity keybar ──────────────────────────────

    fun sendBytes(bytes: ByteArray) {
        sshService?.sendBytes(tabId, bytes)
        _binding?.terminalView?.scrollToBottom()
    }

    fun focusTerminal() {
        _binding?.terminalView?.focusInput()
    }

    fun getSelectedText(): String = _binding?.terminalView?.getSelectedText() ?: ""

    fun pasteText(text: String) {
        _binding?.terminalView?.pasteText(text)
    }

    fun cursorKeyBytes(dir: Char): ByteArray =
        _binding?.terminalView?.cursorKeyBytes(dir) ?: byteArrayOf()

    /** Called by the adapter when the user explicitly closes this tab. */
    fun closeTab() {
        sshService?.disconnectSession(tabId)
        sshService?.removeTab(tabId)
    }

    // ── Status UI ─────────────────────────────────────────────────────────────

    private fun updateStatusUI(status: ConnectionStatus) {
        if (tabId.isNotEmpty()) statusListener?.onTabStatusChanged(tabId, status)
        val cfg = config
        _binding?.btnReconnect?.visibility =
            if (status == ConnectionStatus.DISCONNECTED && cfg != null) View.VISIBLE else View.GONE
        _binding?.statusBar?.text = when (status) {
            ConnectionStatus.NONE         -> "Nincs kapcsolat"
            ConnectionStatus.CONNECTING   -> "Csatlakozás: ${cfg?.displayName()}..."
            ConnectionStatus.CONNECTED    -> "${cfg?.displayName()} ● Csatlakozva"
            ConnectionStatus.DISCONNECTED -> "${cfg?.displayName() ?: ""} ○ Lecsatlakozva"
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveJumpConfig(cfg: ConnectionConfig): ConnectionConfig? {
        if (cfg.jumpConnectionId.isNotBlank()) {
            return SavedConnections.load(requireContext()).find { it.id == cfg.jumpConnectionId }
        }
        if (cfg.jumpHost.isNotBlank()) {
            return ConnectionConfig(
                host = cfg.jumpHost, port = cfg.jumpPort,
                username = cfg.jumpUsername, password = cfg.jumpPassword
            )
        }
        return null
    }

    private fun buildPasswordPrompter(): (String, (String?) -> Unit) -> Unit = { displayHost, callback ->
        val editText = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Jelszó"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            val p = resources.getDimensionPixelSize(R.dimen.dialog_padding)
            setPadding(p, p / 2, p, p / 2)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.KonsoleDialog)
            .setTitle(displayHost)
            .setMessage("Jelszó megadása szükséges")
            .setView(editText)
            .setPositiveButton("OK") { _, _ -> callback(editText.text.toString()) }
            .setNegativeButton("Mégse") { _, _ -> callback(null) }
            .setCancelable(false)
            .show()
    }

    // ── Static content ────────────────────────────────────────────────────────

    private fun showLocalShellPrompt() {
        updateStatusUI(ConnectionStatus.NONE)
        binding.terminalView.horizontalScrollEnabled = false
        binding.terminalView.post { binding.terminalView.setFontSize(16f) }
        val r  = "\u001B[0m"
        val c1 = "\u001B[38;5;214m"; val c2 = "\u001B[38;5;208m"; val c3 = "\u001B[38;5;202m"
        val cs = "\u001B[38;5;244m"; val ch = "\u001B[38;5;240m"
        val title = buildString {
            val colors = listOf(c1, c1, c1, c1, c1, c1, c1, c2, c2, c3)
            "KonsoleSSH".forEachIndexed { i, ch2 -> append("${colors[i]}$ch2") }
            append(r)
        }
        val descLines = listOf(
            "A KDE Konsole ihlette,", "Androidra alkotva.", "",
            "SSH terminál emulátor", "több párhuzamos kapcsolat", "kezelésére, füleken.", "",
            "Jump host támogatással", "belső hálózatok is", "elérhetők.", "",
            "Új kapcsolat: '+' gomb"
        )
        val banner = buildString {
            append("\r\n\r\n  $title\r\n\r\n")
            descLines.forEach { line -> if (line.isEmpty()) append("\r\n") else append("$cs  $line$r\r\n") }
            append("\r\n$ch  Húzz jobbra a súgóért.$r\r\n")
        }
        val bytes = banner.toByteArray(Charsets.UTF_8)
        binding.terminalView.append(bytes, bytes.size)
    }

    private fun showCheatSheet() {
        val r = "\u001b[0m"; val b = "\u001b[1m"
        val h = "\u001b[38;5;214m"; val t = "\u001b[38;5;117m"; val e = "\u001b[38;5;222m"
        val d = "\u001b[38;5;245m"; val w = "\u001b[38;5;203m"; val s = "\u001b[38;5;71m"
        fun sec(name: String) = "\r\n $s$b▸ $name$r\r\n"
        fun cmd(c: String) = "  $t$b$c$r"
        fun desc(text: String) = "    $d$text$r\r\n"
        fun ex(text: String) = "    $e$ $text$r\r\n"
        fun warn(text: String) = "    $w⚠  $text$r\r\n"
        val text = buildString {
            append("\r\n $h$b── Linux parancssori kézikönyv ──$r\r\n")
            append(sec("top — folyamatok és rendszerterhelés"))
            append(cmd("top")); append("\r\n")
            append(desc("Valós idejű nézet: CPU, memória, futó folyamatok."))
            append(desc("Billentyűk: q=kilép  k=kill  M=mem szerint  P=CPU szerint"))
            append(ex("top -b -n1 | head -30"))
            append(sec("df — fájlrendszer lemezhasználat"))
            append(cmd("df -h")); append("\r\n")
            append(desc("-h: emberbarát egységek  -i: inode használat"))
            append(ex("df -h /home")); append(ex("df -h | grep -v tmpfs"))
            append(sec("du — könyvtár/fájl mérete"))
            append(cmd("du -sh *")); append("\r\n")
            append(desc("-s: összesített  -h: emberbarát  --max-depth=1"))
            append(ex("du -sh * | sort -rh | head -10"))
            append(sec("dd — nyers blokk-szintű adatmásolás"))
            append(cmd("dd if=forrás of=cél bs=4M status=progress")); append("\r\n")
            append(warn("Nincs visszavonás. A of= tartalma véglegesen felülíródik!"))
            append(sec("tail / head"))
            append(cmd("tail -f /var/log/syslog")); append("\r\n")
            append(ex("tail -n 50 fájl")); append(ex("head -n 5 /etc/passwd"))
            append(sec("grep / egrep"))
            append(cmd("grep 'minta' fájl")); append("\r\n")
            append(desc("-i: kis/nagybetű  -v: negálás  -n: sorszám  -r: rekurzív"))
            append(ex("grep -rn 'TODO' /home/projekt/"))
            append(ex("egrep '(error|warn|crit)' /var/log/syslog"))
            append(sec("awk — mezőalapú feldolgozás"))
            append(cmd("awk '{print \$1}' fájl")); append("\r\n")
            append(ex("awk -F: '{print \$1}' /etc/passwd"))
            append(ex("df -h | awk 'NR>1 {print \$5, \$6}'"))
            append(sec("sed — folyamszerkesztő"))
            append(cmd("sed 's/régi/új/g' fájl")); append("\r\n")
            append(ex("sed -i 's/foo/bar/g' fajl.txt"))
            append(warn("sed -i felülírja a fájlt!"))
            append(sec("ip — hálózati interfészek"))
            append(cmd("ip addr")); append("\r\n")
            append(ex("ip route")); append(ex("ip neigh"))
            append(sec("mc — Midnight Commander"))
            append(cmd("mc")); append("\r\n")
            append(desc("F5=másol  F6=mozgat  F8=töröl  F9=menü  F10=kilép"))
            append(sec("tr — karaktercsere"))
            append(cmd("echo 'Hello' | tr 'a-z' 'A-Z'")); append("\r\n")
            append(ex("cat fajl | tr -d '\\r'"))
            append("\r\n")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        binding.terminalView.append(bytes, bytes.size)
        binding.terminalView.post { binding.terminalView.scrollToTop() }
    }

    private fun showTmuxSheet() {
        val r = "\u001b[0m"; val b = "\u001b[1m"
        val h = "\u001b[38;5;214m"; val t = "\u001b[38;5;117m"; val e = "\u001b[38;5;222m"
        val d = "\u001b[38;5;245m"; val s = "\u001b[38;5;71m"
        fun sec(name: String) = "\r\n $s$b▸ $name$r\r\n"
        fun cmd(c: String) = "  $t$b$c$r\r\n"
        fun desc(text: String) = "    $d$text$r\r\n"
        fun ex(text: String) = "    $e$ $text$r\r\n"
        val text = buildString {
            append("\r\n $h$b── Tmux kézikönyv ──$r\r\n")
            append(sec("Mi a tmux"))
            append(desc("Terminál multiplexer: több ablak és panel egyetlen terminálon belül."))
            append(desc("Folyamatok futnak kapcsolat megszakadása után is."))
            append(sec("Alapfogalmak"))
            append(desc("Session → teljes munkakörnyezet"))
            append(desc("Window  → fül a sessionön belül"))
            append(desc("Pane    → ablakon belüli osztás"))
            append(sec("Session kezelés"))
            append(cmd("tmux new"))
            append(ex("tmux new -s munka"))
            append(ex("tmux ls"))
            append(ex("tmux attach -t munka"))
            append(ex("tmux attach -d -t munka"))
            append(ex("tmux kill-session -t munka"))
            append(ex("tmux kill-server"))
            append(sec("Prefix"))
            append(cmd("Ctrl+b"))
            append(desc("Minden billentyűparancs ezzel kezdődik."))
            append(sec("Session műveletek"))
            append(cmd("Ctrl+b d        # detach"))
            append(cmd("Ctrl+b \$        # rename session"))
            append(ex("tmux rename-session -t regi uj"))
            append(ex("tmux switch-client -t nev"))
            append(sec("Window műveletek"))
            append(cmd("Ctrl+b c        # új window"))
            append(cmd("Ctrl+b w        # window lista"))
            append(cmd("Ctrl+b n        # következő"))
            append(cmd("Ctrl+b p        # előző"))
            append(cmd("Ctrl+b ,        # átnevezés"))
            append(cmd("Ctrl+b 0..9     # ugrás sorszámra"))
            append(sec("Pane műveletek"))
            append(cmd("Ctrl+b %        # vízszintes osztás"))
            append(cmd("Ctrl+b \"        # függőleges osztás"))
            append(cmd("Ctrl+b o        # következő pane"))
            append(cmd("Ctrl+b ;        # előző pane"))
            append(cmd("Ctrl+b x        # pane bezárása"))
            append(cmd("Ctrl+b z        # zoom (toggle)"))
            append(cmd("Ctrl+b {        # mozgatás balra"))
            append(cmd("Ctrl+b }        # mozgatás jobbra"))
            append(cmd("Ctrl+b !        # pane → window"))
            append(sec("Navigáció"))
            append(cmd("Ctrl+b ←↑→↓    # pane-ek között"))
            append(sec("Resize"))
            append(cmd("Ctrl+b Ctrl+←↑→↓"))
            append(sec("Layout"))
            append(cmd("Ctrl+b Space    # layout váltás"))
            append(sec("Scroll"))
            append(cmd("Ctrl+b [        # scroll mód"))
            append(cmd("q              # kilépés scroll módból"))
            append(sec("Paste"))
            append(cmd("Ctrl+b ]        # beillesztés"))
            append(sec("Config (~/.tmux.conf)"))
            append(ex("set -g mouse on"))
            append(ex("set -g history-limit 100000"))
            append(ex("setw -g mode-keys vi"))
            append(sec("Gyors lista"))
            append(ex("tmux new -s dev"))
            append(ex("tmux ls"))
            append(ex("tmux attach -t dev"))
            append(cmd("Ctrl+b d        # detach"))
            append(cmd("Ctrl+b c        # új window"))
            append(cmd("Ctrl+b %        # vízszintes osztás"))
            append(cmd("Ctrl+b \"        # függőleges osztás"))
            append(cmd("Ctrl+b o        # váltás pane-ek között"))
            append(cmd("Ctrl+b z        # zoom"))
            append(cmd("Ctrl+b [        # scroll"))
            append("\r\n")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        binding.terminalView.append(bytes, bytes.size)
        binding.terminalView.post { binding.terminalView.scrollToTop() }
    }
}
