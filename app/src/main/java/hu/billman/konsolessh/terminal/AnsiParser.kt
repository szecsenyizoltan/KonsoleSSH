package hu.billman.konsolessh.terminal

import android.graphics.Color
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
 */
object AnsiParser {

    // Standard 16-color palette (matches xterm defaults)
    private val ANSI_COLORS = intArrayOf(
        Color.rgb(0,   0,   0),   // 0 black
        Color.rgb(170, 0,   0),   // 1 red
        Color.rgb(0,   170, 0),   // 2 green
        Color.rgb(170, 170, 0),   // 3 yellow
        Color.rgb(0,   0,   170), // 4 blue
        Color.rgb(170, 0,   170), // 5 magenta
        Color.rgb(0,   170, 170), // 6 cyan
        Color.rgb(170, 170, 170), // 7 white
        Color.rgb(85,  85,  85),  // 8 bright black (gray)
        Color.rgb(255, 85,  85),  // 9 bright red
        Color.rgb(85,  255, 85),  // 10 bright green
        Color.rgb(255, 255, 85),  // 11 bright yellow
        Color.rgb(85,  85,  255), // 12 bright blue
        Color.rgb(255, 85,  255), // 13 bright magenta
        Color.rgb(85,  255, 255), // 14 bright cyan
        Color.rgb(255, 255, 255)  // 15 bright white
    )

    // xterm 256-color cube
    private val XTERM_256: IntArray by lazy {
        IntArray(256).also { arr ->
            for (i in 0..15) arr[i] = ANSI_COLORS[i]
            // 216-color cube
            var idx = 16
            for (r in 0..5) for (g in 0..5) for (b in 0..5) {
                arr[idx++] = Color.rgb(
                    if (r == 0) 0 else 55 + r * 40,
                    if (g == 0) 0 else 55 + g * 40,
                    if (b == 0) 0 else 55 + b * 40
                )
            }
            // 24 grayscale
            for (i in 0..23) {
                val v = 8 + i * 10
                arr[idx++] = Color.rgb(v, v, v)
            }
        }
    }

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
                in 30..37 -> state.fg = ANSI_COLORS[code - 30]
                38 -> {
                    val color = readExtendedColor(codes, i + 1)
                    if (color != null) { state.fg = color.first; i += color.second }
                }
                39 -> state.fg = null
                in 40..47 -> state.bg = ANSI_COLORS[code - 40]
                48 -> {
                    val color = readExtendedColor(codes, i + 1)
                    if (color != null) { state.bg = color.first; i += color.second }
                }
                49 -> state.bg = null
                in 90..97  -> state.fg = ANSI_COLORS[code - 90 + 8]
                in 100..107 -> state.bg = ANSI_COLORS[code - 100 + 8]
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
                    val n = codes[start + 1].coerceIn(0, 255)
                    Pair(XTERM_256[n], 2)
                } else null
            }
            2 -> {
                // Truecolor: ESC[38;2;r;g;b
                if (start + 3 < codes.size) {
                    val r = codes[start + 1]; val g = codes[start + 2]; val b = codes[start + 3]
                    Pair(Color.rgb(r, g, b), 4)
                } else null
            }
            else -> null
        }
    }

    private fun SgrState.reset() { fg = null; bg = null; bold = false }
}
