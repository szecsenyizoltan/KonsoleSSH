package hu.billman.konsolessh.terminal

/** xterm billentyű-szekvenciák; a billentyűsor és a hardveres billentyűzet közösen használja. */
object KeySequences {
    const val ESC: Byte = 27
    const val TAB: Byte = 9

    /** ESC + [seq], pl. "OP" = F1, "[15~" = F5. */
    fun escape(seq: String): ByteArray = byteArrayOf(ESC) + seq.toByteArray(Charsets.US_ASCII)

    val FUNCTION_KEYS: List<String> = listOf(
        "OP", "OQ", "OR", "OS", "[15~", "[17~", "[18~", "[19~", "[20~", "[21~", "[23~", "[24~",
    )
}
