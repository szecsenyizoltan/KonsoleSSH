package hu.billman.konsolessh.terminal

import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

/**
 * Parses a subset of ANSI/VT100 escape sequences and converts them to
 * Android Spannable markup. Handles:
 *  - SGR (Select Graphic Rendition): colors (3/4/8-bit), bold, reset
 *  - Cursor movement / erase sequences (stripped silently)
 *
 * A 16- és 256-színű paletta a [SgrPalette] közös objektumból jön, amit a
 * [TerminalBuffer] is használ — így a két ANSI-feldolgozó útvonal (cellarács
 * + Spannable) bit-azonos színekkel dolgozik.
 */
object AnsiParser {

    private data class SgrState(
        var fg: Int? = null,
        var bg: Int? = null,
        var bold: Boolean = false
    )

    /** Regex matching an ANSI CSI sequence (ESC [ ... final) */
    private val CSI_REGEX = Regex("\u001B\\[([0-9;?]*)([A-Za-z])")
    /** Regex matching other ESC sequences (ESC followed by non-[ char or ESC alone) */
    private val ESC_OTHER = Regex("\u001B[^\\[A-Za-z]?")

    fun parse(raw: String): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        val state = SgrState()
        var pos = 0

        while (pos < raw.length) {
            val escIdx = raw.indexOf('\u001B', pos)
            if (escIdx < 0) {
                // No more escapes – append the rest
                ssb.appendStyled(raw.substring(pos), state)
                break
            }
            // Append plain text before escape
            if (escIdx > pos) {
                ssb.appendStyled(raw.substring(pos, escIdx), state)
            }

            // Try to match CSI sequence
            val csiMatch = CSI_REGEX.find(raw, escIdx)
            if (csiMatch != null && csiMatch.range.first == escIdx) {
                val params = csiMatch.groupValues[1]
                val cmd   = csiMatch.groupValues[2]
                if (cmd == "m") applySgr(params, state)
                // All other CSI sequences (cursor movement, erase, etc.) are discarded
                pos = csiMatch.range.last + 1
                continue
            }

            // Try other ESC sequences
            val otherMatch = ESC_OTHER.find(raw, escIdx)
            if (otherMatch != null && otherMatch.range.first == escIdx) {
                pos = otherMatch.range.last + 1
                continue
            }

            // Lone ESC at end of string
            pos = escIdx + 1
        }

        return ssb
    }

    private fun SpannableStringBuilder.appendStyled(text: String, state: SgrState) {
        if (text.isEmpty()) return
        val start = length
        append(text)
        val end = length
        state.fg?.let { setSpan(ForegroundColorSpan(it), start, end, 0) }
        state.bg?.let { setSpan(BackgroundColorSpan(it), start, end, 0) }
        if (state.bold) setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
    }

    private fun applySgr(params: String, state: SgrState) {
        if (params.isEmpty()) { state.reset(); return }
        val codes = params.split(";").mapNotNull { it.toIntOrNull() }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0  -> state.reset()
                1  -> state.bold = true
                22 -> state.bold = false
                in 30..37 -> state.fg = SgrPalette.ansi16(code - 30).argb
                38 -> {
                    val color = readExtendedColor(codes, i + 1)
                    if (color != null) { state.fg = color.first; i += color.second }
                }
                39 -> state.fg = null
                in 40..47 -> state.bg = SgrPalette.ansi16(code - 40).argb
                48 -> {
                    val color = readExtendedColor(codes, i + 1)
                    if (color != null) { state.bg = color.first; i += color.second }
                }
                49 -> state.bg = null
                in 90..97  -> state.fg = SgrPalette.ansi16(code - 90 + 8).argb
                in 100..107 -> state.bg = SgrPalette.ansi16(code - 100 + 8).argb
            }
            i++
        }
    }

    /** Returns (color, params_consumed) or null */
    private fun readExtendedColor(codes: List<Int>, start: Int): Pair<Int, Int>? {
        if (start >= codes.size) return null
        return when (codes[start]) {
            5 -> {
                // 256-color: ESC[38;5;n
                if (start + 1 < codes.size) {
                    Pair(SgrPalette.xterm256(codes[start + 1]).argb, 2)
                } else null
            }
            2 -> {
                // Truecolor: ESC[38;2;r;g;b
                if (start + 3 < codes.size) {
                    val r = codes[start + 1]; val g = codes[start + 2]; val b = codes[start + 3]
                    Pair(TermColor.rgb(r, g, b).argb, 4)
                } else null
            }
            else -> null
        }
    }

    private fun SgrState.reset() { fg = null; bg = null; bold = false }
}
