package hu.billman.konsolessh.terminal

/**
 * Közös ANSI/xterm színpaletta. A két használat — a [TerminalBuffer] cellarács-
 * renderje és az [AnsiParser] Spannable-kimenete — ugyanarra az RGB-re képezi
 * le az SGR-kódokat, csak más típust (TermColor vs Int-span) használ a hívó
 * oldalon. Itt **egy** helyen definiált, egyszer pre-kalkulált paletta,
 * mindkét ág a [TermColor.argb]-ben kapja az eredményt.
 *
 * Pure JVM, [android.graphics.Color]-ra nem hivatkozik — a [TermColor] ARGB-
 * Int-je bit-azonos az `android.graphics.Color.rgb`-vel.
 */
object SgrPalette {

    /** Standard 16-color xterm-paletta (0..15). */
    private val ANSI_16: Array<TermColor> = arrayOf(
        TermColor.rgb(0x00, 0x00, 0x00),   // 0  black
        TermColor.rgb(0xAA, 0x00, 0x00),   // 1  red
        TermColor.rgb(0x00, 0xAA, 0x00),   // 2  green
        TermColor.rgb(0xAA, 0x55, 0x00),   // 3  yellow (xterm amber, TerminalBuffer-kompat)
        TermColor.rgb(0x00, 0x00, 0xAA),   // 4  blue
        TermColor.rgb(0xAA, 0x00, 0xAA),   // 5  magenta
        TermColor.rgb(0x00, 0xAA, 0xAA),   // 6  cyan
        TermColor.rgb(0xAA, 0xAA, 0xAA),   // 7  white
        TermColor.rgb(0x55, 0x55, 0x55),   // 8  bright black (gray)
        TermColor.rgb(0xFF, 0x55, 0x55),   // 9  bright red
        TermColor.rgb(0x55, 0xFF, 0x55),   // 10 bright green
        TermColor.rgb(0xFF, 0xFF, 0x55),   // 11 bright yellow
        TermColor.rgb(0x55, 0x55, 0xFF),   // 12 bright blue
        TermColor.rgb(0xFF, 0x55, 0xFF),   // 13 bright magenta
        TermColor.rgb(0x55, 0xFF, 0xFF),   // 14 bright cyan
        TermColor.rgb(0xFF, 0xFF, 0xFF),   // 15 bright white
    )

    /**
     * Xterm 256-palette: 16 ANSI + 216 cube + 24 grayscale. Lazy precompute.
     */
    private val XTERM_256: Array<TermColor> by lazy {
        Array(256) { idx ->
            when {
                idx < 16 -> ANSI_16[idx]
                idx < 232 -> {
                    // 6x6x6 cube: idx − 16 = r*36 + g*6 + b
                    val t = idx - 16
                    val r = t / 36
                    val g = (t % 36) / 6
                    val b = t % 6
                    TermColor.rgb(cubeComp(r), cubeComp(g), cubeComp(b))
                }
                else -> {
                    // 24-step grayscale
                    val v = 8 + (idx - 232) * 10
                    TermColor.rgb(v, v, v)
                }
            }
        }
    }

    /** xterm cube-komponens: 0 → 0, 1..5 → 55 + n*40 */
    private fun cubeComp(n: Int): Int = if (n == 0) 0 else 55 + n * 40

    /** SGR 30-37, 40-47, 90-97, 100-107 körbe tartozó 16-szín. */
    fun ansi16(index: Int): TermColor = ANSI_16[index.coerceIn(0, 15)]

    /** SGR 38;5;N és 48;5;N párokhoz a 256-paletta. */
    fun xterm256(index: Int): TermColor = XTERM_256[index.coerceIn(0, 255)]
}
