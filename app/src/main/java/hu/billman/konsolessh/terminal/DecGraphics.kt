package hu.billman.konsolessh.terminal

/**
 * DEC Special Graphics (ESC ( 0): a 0x5F–0x7E tartományt vonalrajzoló és
 * egyéb jelekre képezi. Régebbi ncurses-, dialog- és mc-keretek ezt használják;
 * nélküle a keret helyén `lqqqk` betűk jelennek meg.
 */
internal object DecGraphics {

    private const val FIRST = '_'
    private const val TABLE = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"

    fun map(ch: Char): Char = if (ch in FIRST..'~') TABLE[ch - FIRST] else ch
}
