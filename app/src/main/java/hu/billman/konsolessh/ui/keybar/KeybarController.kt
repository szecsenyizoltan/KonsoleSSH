package hu.billman.konsolessh.ui.keybar

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import hu.billman.konsolessh.R
import hu.billman.konsolessh.databinding.ActivityMainBinding
import hu.billman.konsolessh.terminal.KeySequences
import hu.billman.konsolessh.terminal.Modifier
import hu.billman.konsolessh.terminal.ModifierState
import hu.billman.konsolessh.terminal.TerminalClipboard
import hu.billman.konsolessh.terminal.TerminalView

/**
 * A képernyő alsó billentyűsora: ragadós módosítók, Esc/Tab, nyilak, F1–F12,
 * Ctrl-kombinációk, billentyűzet és feltöltés gomb. Állapotot nem tart: a
 * módosítókat a ViewModel adja ([render]), a bevitel a [Host]-on át megy.
 */
class KeybarController(private val binding: ActivityMainBinding, private val host: Host) {

    interface Host {
        /** Az aktuális oldal terminálnézete (rögzített oldalon is lehet). */
        fun currentTerminal(): TerminalView?

        /** Bevitel a ragadós módosítókkal együtt. */
        fun sendKey(bytes: ByteArray)

        /** Vezérlőkód a módosítók érintése nélkül (Ctrl-sor). */
        fun sendRaw(bytes: ByteArray)

        fun toggleModifier(modifier: Modifier)
        fun pickUploadFile()
        fun showMessage(message: String)
    }

    private val context = binding.root.context
    private val hints = listOf(
        ScrollHint(binding.keybarScrollView, binding.keybarScrollHint, binding.keybarScrollHintLeft),
        ScrollHint(binding.fnbarScrollView, binding.fnbarScrollHint, binding.fnbarScrollHintLeft),
    )
    private val modifierButtons = mapOf(
        Modifier.CTRL to binding.btnModCtrl,
        Modifier.SHIFT to binding.btnModShift,
        Modifier.ALT to binding.btnModAlt,
        Modifier.ALTGR to binding.btnModAltGr,
    )

    init {
        binding.btnKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnFn.setOnClickListener { toggleBar(binding.fnbarContainer, binding.btnFn) }
        binding.btnArrowToggle.setOnClickListener { toggleBar(binding.arrowbarContainer, binding.btnArrowToggle) }
        binding.btnUpload.setOnClickListener { host.pickUploadFile() }
        modifierButtons.forEach { (modifier, button) ->
            button.setOnClickListener {
                host.toggleModifier(modifier)
                focusTerminal()
            }
        }
        key(binding.btnEscape) { byteArrayOf(KeySequences.ESC) }
        key(binding.btnTab) { byteArrayOf(KeySequences.TAB) }
        mapOf(
            binding.btnArrowUp to 'A',
            binding.btnArrowDown to 'B',
            binding.btnArrowRight to 'C',
            binding.btnArrowLeft to 'D',
        ).forEach { (button, dir) -> key(button) { host.currentTerminal()?.cursorKeyBytes(dir) ?: byteArrayOf() } }
        listOf(
            binding.btnF1, binding.btnF2, binding.btnF3, binding.btnF4, binding.btnF5, binding.btnF6,
            binding.btnF7, binding.btnF8, binding.btnF9, binding.btnF10, binding.btnF11, binding.btnF12,
        ).forEachIndexed { i, button -> key(button) { KeySequences.escape(KeySequences.FUNCTION_KEYS[i]) } }
        setupCtrlbar()
    }

    /** A módosítók állapotát tükrözi a gombokon; aktív Ctrl mellett a Ctrl-sor látszik. */
    fun render(state: ModifierState) {
        modifierButtons.forEach { (modifier, button) -> highlight(button, modifier in state) }
        binding.ctrlbarContainer.visibility = if (Modifier.CTRL in state) View.VISIBLE else View.GONE
    }

    fun saveState(out: Bundle) {
        out.putBoolean(STATE_FNBAR, binding.fnbarContainer.isVisible)
        out.putBoolean(STATE_ARROWBAR, binding.arrowbarContainer.isVisible)
    }

    fun restoreState(saved: Bundle?) {
        saved ?: return
        if (saved.getBoolean(STATE_FNBAR)) toggleBar(binding.fnbarContainer, binding.btnFn)
        if (saved.getBoolean(STATE_ARROWBAR)) toggleBar(binding.arrowbarContainer, binding.btnArrowToggle)
    }

    fun dispose() = hints.forEach(ScrollHint::dispose)

    private fun key(button: Button, bytes: () -> ByteArray) {
        button.setOnClickListener {
            flash(button)
            val data = bytes()
            if (data.isNotEmpty()) host.sendKey(data)
            focusTerminal()
        }
    }

    private fun setupCtrlbar() {
        val container = binding.ctrlbarButtons
        CTRL_KEYS.forEach { c ->
            val button = LayoutInflater.from(context).inflate(R.layout.item_keybar_button, container, false) as Button
            button.text = context.getString(R.string.ctrl_key_label, c)
            button.setOnClickListener {
                flash(button)
                when (c) {
                    'C' -> copyOrInterrupt()
                    'V' -> paste()
                    else -> sendControl(c)
                }
            }
            container.addView(button)
        }
    }

    // Ctrl+C copies a selection if there is one, otherwise it is the interrupt.
    private fun copyOrInterrupt() {
        val selection = host.currentTerminal()?.getSelectedText().orEmpty()
        if (selection.isNotEmpty()) {
            TerminalClipboard.text = selection
            host.showMessage(context.getString(R.string.copied))
        } else {
            sendControl('C')
        }
    }

    private fun paste() {
        val clip = TerminalClipboard.text
        if (!clip.isNullOrEmpty()) {
            host.currentTerminal()?.pasteText(clip)
            host.showMessage(context.getString(R.string.pasted))
        }
    }

    private fun sendControl(c: Char) {
        host.sendRaw(byteArrayOf((c.code - 'A'.code + 1).toByte()))
        focusTerminal()
    }

    private fun toggleBar(bar: View, button: Button) {
        val show = bar.visibility != View.VISIBLE
        bar.visibility = if (show) View.VISIBLE else View.GONE
        highlight(button, show)
        focusTerminal()
    }

    private fun toggleKeyboard() {
        val imm = context.getSystemService<InputMethodManager>() ?: return
        val imeVisible = ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (imeVisible) {
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        } else {
            host.currentTerminal()?.focusAndShowKeyboard()
        }
    }

    private fun focusTerminal() {
        host.currentTerminal()?.focusInput()
    }

    private fun highlight(button: Button, active: Boolean) {
        val color = if (active) ContextCompat.getColor(context, R.color.keybar_mod_active) else Color.TRANSPARENT
        button.setBackgroundColor(color)
    }

    private fun flash(button: Button) {
        button.setTextColor(ContextCompat.getColor(context, R.color.accent))
        button.postDelayed({ button.setTextColor(ContextCompat.getColor(context, R.color.keybar_key_text)) }, FLASH_MS)
    }

    private companion object {
        val CTRL_KEYS = listOf('A', 'B', 'C', 'D', 'V', 'Z')
        const val FLASH_MS = 300L
        const val STATE_FNBAR = "fnbar_visible"
        const val STATE_ARROWBAR = "arrowbar_visible"
    }
}
