package hu.billman.konsolessh.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Modifier { CTRL, SHIFT, ALT, ALTGR }

/** A billentyűsor ragadós módosítói; egy leütés után visszaállnak. */
data class ModifierState(val active: Set<Modifier> = emptySet()) {

    operator fun contains(modifier: Modifier): Boolean = modifier in active

    /** A leütést a módosítóknak megfelelő bájtsorrá alakítja (Ctrl+betű, Alt = ESC-prefix). */
    fun applyTo(bytes: ByteArray): ByteArray {
        if (active.isEmpty()) return bytes
        var result = bytes
        if (Modifier.CTRL in this && result.size == 1) result = ctrl(result)
        if (Modifier.SHIFT in this && result.size == 1) {
            val b = result[0].toInt() and BYTE_MASK
            if (b in 'a'.code..'z'.code) result = byteArrayOf((b - CASE_OFFSET).toByte())
        }
        if (Modifier.ALT in this || Modifier.ALTGR in this) result = byteArrayOf(KeySequences.ESC) + result
        return result
    }

    private fun ctrl(bytes: ByteArray): ByteArray {
        val b = bytes[0].toInt() and BYTE_MASK
        return when (b) {
            in 'a'.code..'z'.code -> byteArrayOf((b - 'a'.code + 1).toByte())
            in 'A'.code..'Z'.code -> byteArrayOf((b - 'A'.code + 1).toByte())
            ' '.code -> byteArrayOf(0)
            '['.code -> byteArrayOf(KeySequences.ESC)
            else -> bytes
        }
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val CASE_OFFSET = 32
    }
}

class KeyModifiers {

    private val _state = MutableStateFlow(ModifierState())
    val state: StateFlow<ModifierState> = _state.asStateFlow()

    fun toggle(modifier: Modifier) = _state.update {
        ModifierState(if (modifier in it) it.active - modifier else it.active + modifier)
    }

    /** Alkalmazza és visszaállítja a módosítókat. */
    fun consume(bytes: ByteArray): ByteArray {
        val current = _state.value
        if (current.active.isNotEmpty()) _state.value = ModifierState()
        return current.applyTo(bytes)
    }
}
