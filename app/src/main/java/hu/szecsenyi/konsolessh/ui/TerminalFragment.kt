package hu.szecsenyi.konsolessh.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import hu.szecsenyi.konsolessh.R
import hu.szecsenyi.konsolessh.databinding.FragmentTerminalBinding
import hu.szecsenyi.konsolessh.model.ConnectionConfig
import hu.szecsenyi.konsolessh.model.SavedConnections
import hu.szecsenyi.konsolessh.ssh.SshSession
import hu.szecsenyi.konsolessh.terminal.AppClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    }

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!
    val terminalView get() = _binding?.terminalView

    private var sshSession: SshSession? = null
    private var readJob: Job? = null
    private var config: ConnectionConfig? = null
    var tabId: String = ""; private set

    private var statusListener: TabStatusListener? = null

    // ── Sticky modifier state ─────────────────────────────────────────────────
    private var modCtrl  = false
    private var modShift = false
    private var modAlt   = false
    private var modAltGr = false

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

        // Keyboard input: apply modifiers then reset them (one-shot)
        binding.terminalView.onKeyInput = { bytes ->
            sshSession?.sendBytes(applyModifiers(bytes))
            resetModifiers()
        }

        // Forward terminal resize to SSH PTY
        binding.terminalView.onTerminalResize = { cols, rows ->
            sshSession?.resize(cols, rows)
        }

        // Keyboard toggle
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }

        // Sticky modifier toggles
        binding.btnModCtrl.setOnClickListener  { toggleMod(binding.btnModCtrl,  ::modCtrl) }
        binding.btnModShift.setOnClickListener { toggleMod(binding.btnModShift, ::modShift) }
        binding.btnModAlt.setOnClickListener   { toggleMod(binding.btnModAlt,   ::modAlt) }
        binding.btnModAltGr.setOnClickListener { toggleMod(binding.btnModAltGr, ::modAltGr) }

        // Action keys (apply+reset modifiers)
        binding.btnEscape.setOnClickListener   { flashButton(binding.btnEscape);    sendKey(byteArrayOf(27)) }
        binding.btnTab.setOnClickListener      { flashButton(binding.btnTab);       sendKey(byteArrayOf(9)) }
        binding.btnCtrlC.setOnClickListener {
            flashButton(binding.btnCtrlC)
            val sel = binding.terminalView.getSelectedText()
            if (sel.isNotEmpty()) {
                AppClipboard.text = sel
                KonsoleToast.show(binding.root, "Másolva")
            } else {
                sendKey(byteArrayOf(3))
            }
        }
        binding.btnCtrlD.setOnClickListener {
            flashButton(binding.btnCtrlD)
            sendKey(byteArrayOf(4))
        }
        binding.btnCtrlV.setOnClickListener {
            flashButton(binding.btnCtrlV)
            val clip = AppClipboard.text
            if (!clip.isNullOrEmpty()) {
                binding.terminalView.pasteText(clip)
                KonsoleToast.show(binding.root, "Beillesztve")
            }
        }
        binding.btnCtrlZ.setOnClickListener {
            flashButton(binding.btnCtrlZ)
            sendKey(byteArrayOf(26))
        }
        binding.btnArrowUp.setOnClickListener    { flashButton(binding.btnArrowUp);    sendKey(binding.terminalView.cursorKeyBytes('A')) }
        binding.btnArrowDown.setOnClickListener  { flashButton(binding.btnArrowDown);  sendKey(binding.terminalView.cursorKeyBytes('B')) }
        binding.btnArrowLeft.setOnClickListener  { flashButton(binding.btnArrowLeft);  sendKey(binding.terminalView.cursorKeyBytes('D')) }
        binding.btnArrowRight.setOnClickListener { flashButton(binding.btnArrowRight); sendKey(binding.terminalView.cursorKeyBytes('C')) }

        // F-keys
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

        val cfg = config
        when {
            cfg != null && cfg.host.isNotBlank() -> connectSsh(cfg)
            arguments?.getString(ARG_MODE) == MODE_CHEAT -> showCheatSheet()
            else -> showLocalShellPrompt()
        }
    }

    // ── Keybar scroll hint ────────────────────────────────────────────────────

    private var hintAnimatorRight: ObjectAnimator? = null
    private var hintAnimatorLeft: ObjectAnimator? = null
    private var fnHintAnimatorRight: ObjectAnimator? = null
    private var fnHintAnimatorLeft: ObjectAnimator? = null

    private fun setupKeybarScrollHint() {
        val scrollView = binding.keybarScrollView
        val hintRight = binding.keybarScrollHint
        val hintLeft = binding.keybarScrollHintLeft
        val interp = android.view.animation.AccelerateDecelerateInterpolator()

        hintAnimatorRight = ObjectAnimator.ofFloat(hintRight, "translationX", 0f, -8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = interp
        }
        hintAnimatorLeft = ObjectAnimator.ofFloat(hintLeft, "translationX", 0f, 8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = interp
        }

        fun updateHints() {
            val canRight = scrollView.canScrollHorizontally(1)
            val canLeft  = scrollView.canScrollHorizontally(-1)

            if (canRight && hintRight.visibility != View.VISIBLE) {
                hintRight.visibility = View.VISIBLE
                hintAnimatorRight?.start()
            } else if (!canRight && hintRight.visibility == View.VISIBLE) {
                hintRight.visibility = View.GONE
                hintAnimatorRight?.cancel()
                hintRight.translationX = 0f
            }

            if (canLeft && hintLeft.visibility != View.VISIBLE) {
                hintLeft.visibility = View.VISIBLE
                hintAnimatorLeft?.start()
            } else if (!canLeft && hintLeft.visibility == View.VISIBLE) {
                hintLeft.visibility = View.GONE
                hintAnimatorLeft?.cancel()
                hintLeft.translationX = 0f
            }
        }

        hintRight.setOnClickListener { scrollView.smoothScrollBy(scrollView.width / 2, 0) }
        hintLeft.setOnClickListener  { scrollView.smoothScrollBy(-(scrollView.width / 2), 0) }

        scrollView.viewTreeObserver.addOnGlobalLayoutListener { updateHints() }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateHints() }
    }

    private fun setupFnbarScrollHint() {
        val scrollView = binding.fnbarScrollView
        val hintRight  = binding.fnbarScrollHint
        val hintLeft   = binding.fnbarScrollHintLeft
        val interp = android.view.animation.AccelerateDecelerateInterpolator()

        fnHintAnimatorRight = ObjectAnimator.ofFloat(hintRight, "translationX", 0f, -8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = interp
        }
        fnHintAnimatorLeft = ObjectAnimator.ofFloat(hintLeft, "translationX", 0f, 8f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = interp
        }

        fun updateHints() {
            val canRight = scrollView.canScrollHorizontally(1)
            val canLeft  = scrollView.canScrollHorizontally(-1)

            if (canRight && hintRight.visibility != View.VISIBLE) {
                hintRight.visibility = View.VISIBLE; fnHintAnimatorRight?.start()
            } else if (!canRight && hintRight.visibility == View.VISIBLE) {
                hintRight.visibility = View.GONE; fnHintAnimatorRight?.cancel(); hintRight.translationX = 0f
            }
            if (canLeft && hintLeft.visibility != View.VISIBLE) {
                hintLeft.visibility = View.VISIBLE; fnHintAnimatorLeft?.start()
            } else if (!canLeft && hintLeft.visibility == View.VISIBLE) {
                hintLeft.visibility = View.GONE; fnHintAnimatorLeft?.cancel(); hintLeft.translationX = 0f
            }
        }

        hintRight.setOnClickListener { scrollView.smoothScrollBy(scrollView.width / 2, 0) }
        hintLeft.setOnClickListener  { scrollView.smoothScrollBy(-(scrollView.width / 2), 0) }

        scrollView.viewTreeObserver.addOnGlobalLayoutListener { updateHints() }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateHints() }
    }

    // ── Modifier logic ────────────────────────────────────────────────────────

    private fun toggleMod(button: Button, prop: kotlin.reflect.KMutableProperty0<Boolean>) {
        prop.set(!prop.get())
        updateModButton(button, prop.get())
        binding.terminalView.focusInput()
    }

    private fun updateModButton(button: Button, active: Boolean) {
        if (active) {
            button.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.keybar_mod_active))
        } else {
            button.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun flashButton(button: Button) {
        val accent = ContextCompat.getColor(requireContext(), R.color.accent)
        button.setTextColor(accent)
        button.postDelayed({
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.keybar_key_text))
        }, 300)
    }

    private fun applyModifiers(bytes: ByteArray): ByteArray {
        if (!modCtrl && !modShift && !modAlt && !modAltGr) return bytes

        var result = bytes

        // Ctrl: convert single letter to control code
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

        // Shift: uppercase single lowercase letter (keyboard usually handles this)
        if (modShift && result.size == 1) {
            val b = result[0].toInt() and 0xFF
            if (b in 'a'.code..'z'.code) result = byteArrayOf((b - 32).toByte())
        }

        // Alt / AltGr: prepend ESC
        if (modAlt || modAltGr) result = byteArrayOf(27) + result

        return result
    }

    private fun resetModifiers() {
        if (modCtrl)  { modCtrl  = false; _binding?.let { updateModButton(it.btnModCtrl,  false) } }
        if (modShift) { modShift = false; _binding?.let { updateModButton(it.btnModShift, false) } }
        if (modAlt)   { modAlt   = false; _binding?.let { updateModButton(it.btnModAlt,   false) } }
        if (modAltGr) { modAltGr = false; _binding?.let { updateModButton(it.btnModAltGr, false) } }
    }

    // ── Key sending ───────────────────────────────────────────────────────────

    private var keyboardVisible = false

    private fun toggleKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>() ?: return
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(binding.terminalView.windowToken, 0)
            keyboardVisible = false
        } else {
            binding.terminalView.focusInput()
            keyboardVisible = true
        }
    }

    private fun fnKey(seq: String) = byteArrayOf(27) + seq.toByteArray(Charsets.US_ASCII)

    /** Send bytes from a key-bar button: apply modifiers, reset, refocus. */
    private fun sendKey(bytes: ByteArray) {
        sshSession?.sendBytes(applyModifiers(bytes))
        resetModifiers()
        binding.terminalView.focusInput()
        binding.terminalView.scrollToBottom()
    }

    // ── SSH ───────────────────────────────────────────────────────────────────

    private fun showLocalShellPrompt() {
        emitStatus(ConnectionStatus.NONE)
        binding.statusBar.text = "Nincs kapcsolat"
        binding.terminalView.horizontalScrollEnabled = false
        binding.terminalView.post { binding.terminalView.setFontSize(16f) }
        val r  = "\u001B[0m"
        val c1 = "\u001B[38;5;214m"  // arany
        val c2 = "\u001B[38;5;208m"  // narancs
        val c3 = "\u001B[38;5;202m"  // sötét narancs
        val cs = "\u001B[38;5;244m"  // szürke
        val ch = "\u001B[38;5;240m"  // sötét szürke
        // "KonsoleSSH" — minden betű más árnyalat az arany-narancs skálán
        val title = buildString {
            val colors = listOf(c1, c1, c1, c1, c1, c1, c1, c2, c2, c3)
            val word   = "KonsoleSSH"
            word.forEachIndexed { i, ch2 -> append("${colors[i]}${ch2}") }
            append(r)
        }
        val descLines = listOf(
            "A KDE Konsole ihlette,",
            "Androidra alkotva.",
            "",
            "SSH terminál emulátor",
            "több párhuzamos kapcsolat",
            "kezelésére, füleken.",
            "",
            "Jump host támogatással",
            "belső hálózatok is",
            "elérhetők.",
            "",
            "Új kapcsolat: '+' gomb"
        )
        val banner = buildString {
            append("\r\n\r\n")
            append("  $title\r\n")
            append("\r\n")
            descLines.forEach { line ->
                if (line.isEmpty()) append("\r\n")
                else append("$cs  $line$r\r\n")
            }
            append("\r\n")
            append("$ch  Húzz jobbra a súgóért.$r\r\n")
        }
        val bytes = banner.toByteArray(Charsets.UTF_8)
        binding.terminalView.append(bytes, bytes.size)
    }

    private fun showCheatSheet() {
        binding.terminalView.post { binding.terminalView.setFontSize(14f) }
        val r  = "\u001b[0m"
        val b  = "\u001b[1m"
        val h  = "\u001b[38;5;214m"   // narancs — főcím
        val t  = "\u001b[38;5;117m"   // kék — parancs neve
        val e  = "\u001b[38;5;222m"   // sárga — példa
        val d  = "\u001b[38;5;245m"   // szürke — leírás
        val w  = "\u001b[38;5;203m"   // piros — figyelmeztetés
        val s  = "\u001b[38;5;71m"    // zöld — szekció
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
            append(desc("Billentyűk: q=kilép  k=kill (PID megadás)  h=súgó"))
            append(desc("           M=mem szerint rendez  P=CPU szerint"))
            append(ex("top -b -n1 | head -30   # egyszeri kimenet (scripthez)"))

            append(sec("df — fájlrendszer lemezhasználat"))
            append(cmd("df -h")); append("\r\n")
            append(desc("Partíciók mérete, foglalt/szabad hely, csatolási pont."))
            append(desc("-h: emberbarát egységek (G, M)  -i: inode használat"))
            append(ex("df -h /home              # csak /home partíció"))
            append(ex("df -h | grep -v tmpfs    # ideiglen. fs kiszűrve"))

            append(sec("du — könyvtár/fájl mérete"))
            append(cmd("du -sh *")); append("\r\n")
            append(desc("Megmutatja az összes elem méretét az aktuális könyvtárban."))
            append(desc("-s: összesített  -h: emberbarát  --max-depth=1"))
            append(ex("du -sh /var/*             # /var alkönyvtárai"))
            append(ex("du -sh * | sort -rh | head -10  # top 10 legnagyobb"))

            append(sec("dd — nyers blokk-szintű adatmásolás"))
            append(cmd("dd if=forrás of=cél bs=4M status=progress")); append("\r\n")
            append(desc("if=bemeneti fájl/eszköz  of=kimeneti fájl/eszköz"))
            append(desc("bs=blokk méret  count=blokkok száma  status=progress"))
            append(ex("dd if=/dev/sda of=/dev/sdb bs=4M status=progress"))
            append(ex("dd if=/dev/zero of=teszt.img bs=1M count=100"))
            append(ex("dd if=/dev/urandom of=véletlen.bin bs=1M count=10"))
            append(warn("Nincs visszavonás. A of= tartalma véglegesen felülíródik!"))

            append(sec("tail — fájl vége"))
            append(cmd("tail fájl")); append("\r\n")
            append(desc("Alapból az utolsó 10 sort írja ki."))
            append(desc("-n N: utolsó N sor  -f: valós idejű figyelés  -F: rotate-t is követ"))
            append(ex("tail -n 50 /var/log/syslog"))
            append(ex("tail -f /var/log/nginx/access.log"))
            append(ex("tail -f /var/log/syslog | grep -i error"))

            append(sec("head — fájl eleje"))
            append(cmd("head fájl")); append("\r\n")
            append(desc("Alapból az első 10 sort írja ki."))
            append(desc("-n N: első N sor  -c N: első N bájt"))
            append(ex("head -n 5 /etc/passwd"))
            append(ex("head -c 512 bináris.bin | xxd"))

            append(sec("tee — kimenet elágaztatása"))
            append(cmd("cmd | tee fájl")); append("\r\n")
            append(desc("A kimenet egyszerre megjelenik a képernyőn ÉS kerül a fájlba."))
            append(desc("-a: hozzáfűzés (felülírás helyett)"))
            append(ex("make 2>&1 | tee build.log"))
            append(ex("apt upgrade 2>&1 | tee -a upgrade.log"))

            append(sec("wc — sorok, szavak, bájtok számlálása"))
            append(cmd("wc fájl")); append("\r\n")
            append(desc("Kimenet: sorok  szavak  bájtok  fájlnév"))
            append(desc("-l: csak sorok  -w: csak szavak  -c: csak bájtok"))
            append(ex("wc -l /etc/passwd          # hány felhasználó?"))
            append(ex("ls /etc | wc -l            # hány fájl van /etc-ben?"))
            append(ex("cat fájl | grep hiba | wc -l"))

            append(sec("grep — szöveg keresése (alap regex)"))
            append(cmd("grep 'minta' fájl")); append("\r\n")
            append(desc("-i: kis/nagybetű független  -v: negálás (ami NEM illeszkedik)"))
            append(desc("-n: sorszámmal  -r: rekurzív  -l: csak fájlnév  -c: darabszám"))
            append(ex("grep -rn 'TODO' /home/projekt/"))
            append(ex("grep -v '^#' /etc/ssh/sshd_config  # komment nélkül"))
            append(ex("grep -i 'error\\|warn' /var/log/syslog"))

            append(sec("egrep — kiterjesztett regex (= grep -E)"))
            append(cmd("egrep 'minta' fájl")); append("\r\n")
            append(desc("Ugyanaz mint grep -E. |, +, ?, (), {} extra karakterek."))
            append(ex("egrep '(error|warn|crit)' /var/log/syslog"))
            append(ex("egrep '^[0-9]{4}-[0-9]{2}' access.log"))
            append(ex("egrep -v '^\\s*(#|$)' /etc/fstab   # érdemi sorok"))

            append(sec("mc — Midnight Commander fájlkezelő"))
            append(cmd("mc")); append("\r\n")
            append(desc("Kétpaneles Norton Commander-stílusú fájlkezelő."))
            append(desc("F5=másol  F6=mozgat  F8=töröl  F9=menü  F10=kilép"))
            append(desc("Tab=panel váltás  Ins=jelöl  Ctrl+O=terminál előtér"))
            append(ex("mc -b                      # fekete-fehér mód"))
            append(ex("mc /var/log /tmp           # könyvtárak megnyitva"))
            append(desc("Ctrl+O után a mc fut háttérben; 'exit' visszahoz."))

            append("\r\n")
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        binding.terminalView.append(bytes, bytes.size)
        binding.terminalView.post { binding.terminalView.scrollToTop() }
    }

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

    private fun friendlyError(err: Throwable): String {
        // Collect all messages in the cause chain
        val parts = buildList {
            var t: Throwable? = err
            while (t != null) { t.message?.let { add(it) }; t = t.cause }
        }
        val full = parts.joinToString(" ").lowercase()
        return when {
            "connection refused"     in full -> "A kapcsolat elutasítva — a szerver nem fogad kapcsolatot ezen a porton."
            "connection timed out"   in full ||
            "connect timed out"      in full ||
            "timed out"              in full ||
            "timeout"                in full -> "Időtúllépés — a szerver nem válaszol (tűzfal vagy helytelen cím?)."
            "no route to host"       in full -> "Nem érhető el a szerver — ellenőrizd a hálózatot és az IP-t."
            "network is unreachable" in full ||
            "unreachable"            in full -> "A hálózat nem érhető el."
            "unknown host"           in full ||
            "nodename nor servname"  in full -> "Ismeretlen hostnév — DNS hiba vagy elgépelés."
            "auth fail"              in full ||
            "authentication"         in full -> "Hitelesítés sikertelen — helytelen jelszó vagy kulcs."
            "userauth fail"          in full -> "Hitelesítés sikertelen — a szerver visszautasította."
            "connection is closed"   in full ||
            "closed by foreign host" in full -> "A kapcsolat váratlanul lezárult."
            "broken pipe"            in full -> "A kapcsolat megszakadt (broken pipe)."
            "port forwarding"        in full -> "Port forwarding hiba — a jump szerver nem engedélyezi."
            "channel"                in full -> "SSH csatorna hiba — a szerver lezárta a munkamenetet."
            else -> {
                // Strip JSch/java class name prefixes for a cleaner fallback
                parts.firstOrNull()
                    ?.replace(Regex("^session\\.connect:\\s*"), "")
                    ?.replace(Regex("^[a-zA-Z]+(\\.[a-zA-Z]+)+:\\s*"), "")
                    ?: err.javaClass.simpleName
            }
        }
    }

    private fun connectSsh(cfg: ConnectionConfig) {
        val jumpConfig = resolveJumpConfig(cfg)
        if (cfg.jumpConnectionId.isNotBlank() && jumpConfig == null) {
            binding.terminalView.append(
                "Hiba: a jump kapcsolat (${cfg.jumpConnectionId}) nem található a mentett kapcsolatok között.\r\n".toByteArray(),
                0
            )
            emitStatus(ConnectionStatus.DISCONNECTED)
            return
        }
        val session = SshSession(cfg, jumpConfig)
        session.passwordPrompter = { displayHost, callback ->
            val editText = android.widget.EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Jelszó"
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(android.graphics.Color.GRAY)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
        sshSession = session
        emitStatus(ConnectionStatus.CONNECTING)
        binding.statusBar.text = "Csatlakozás: ${cfg.displayName()}..."
        if (jumpConfig == null) {
            val initMsg = "Csatlakozás: ${cfg.host}:${cfg.port}...\r\n"
            binding.terminalView.append(initMsg.toByteArray(), initMsg.length)
        }
        val initCols = binding.terminalView.currentCols
        val initRows = binding.terminalView.currentRows
        lifecycleScope.launch {
            session.connect(
                termCols = initCols,
                termRows = initRows,
                onProgress = { msg ->
                    binding.terminalView.append(msg.toByteArray(), msg.length)
                },
                onConnected = {
                    emitStatus(ConnectionStatus.CONNECTED)
                    binding.statusBar.text = "${cfg.displayName()} ● Csatlakozva"
                    startReading(session)
                    binding.terminalView.focusInput()
                },
                onError = { err ->
                    emitStatus(ConnectionStatus.DISCONNECTED)
                    binding.statusBar.text = "Kapcsolat sikertelen"
                    val msg = "Hiba: ${friendlyError(err)}\r\n"
                    binding.terminalView.append(msg.toByteArray(), msg.length)
                }
            )
        }
    }

    private fun startReading(session: SshSession) {
        readJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            val stream = session.inputStream ?: return@launch
            try {
                while (isActive && session.isConnected) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val copy = buf.copyOf(n)
                        launch(Dispatchers.Main) { _binding?.terminalView?.append(copy, copy.size) }
                    }
                }
            } catch (_: Exception) {}
            launch(Dispatchers.Main) {
                emitStatus(ConnectionStatus.DISCONNECTED)
                _binding?.statusBar?.text = "${config?.displayName() ?: ""} ○ Lecsatlakozva"
                _binding?.terminalView?.append("\r\n[Kapcsolat lezárva]\r\n".toByteArray(), "\r\n[Kapcsolat lezárva]\r\n".length)
            }
        }
    }

    private fun emitStatus(status: ConnectionStatus) {
        if (tabId.isNotEmpty()) statusListener?.onTabStatusChanged(tabId, status)
    }

    fun disconnectAndClose() {
        readJob?.cancel()
        sshSession?.disconnect()
    }

    override fun onDestroyView() {
        hintAnimatorRight?.cancel()
        hintAnimatorLeft?.cancel()
        hintAnimatorRight = null
        hintAnimatorLeft = null
        fnHintAnimatorRight?.cancel()
        fnHintAnimatorLeft?.cancel()
        fnHintAnimatorRight = null
        fnHintAnimatorLeft = null
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAndClose()
    }
}
