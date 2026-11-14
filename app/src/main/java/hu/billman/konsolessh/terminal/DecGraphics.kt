package hu.billman.konsolessh.terminal

/**
 * DEC Special Graphics (ESC ( 0): maps 0x5F–0x7E to line-drawing and other
 * symbols. Older ncurses, dialog and mc frames use it; without it, letters
 * such as `lqqqk` appear where the frame should be.
 */
internal object DecGraphics {

    private const val FIRST = '_'
    private const val TABLE = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

    fun map(ch: Char): Char = if (ch in FIRST..'~') TABLE[ch - FIRST] else ch
}
