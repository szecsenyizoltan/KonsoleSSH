package hu.billman.konsolessh.terminal

/**
 * A text selection in logical coordinates: line index into scrollback plus
 * screen ([TerminalBuffer.lineAt]) and column. [startLine] is where the
 * press began, [endLine] where the finger is now, so the end may come
 * before the start; [bounds] puts them in reading order.
 */
internal class TerminalSelection {

    var active = false
        private set
    var startLine = 0
        private set
    var endLine = 0
        private set
    private var startCol = 0
    private var endCol = 0

    fun start(line: Int, col: Int) {
        startLine = line
        startCol = col
        endLine = line
        endCol = col
        active = true
    }

    fun extendTo(line: Int, col: Int) {
        endLine = line
        endCol = col
    }

    fun clear() {
        active = false
    }

    /**
     * First line, first column, last line, last column, in reading order.
     * An array, because the renderer asks for it once per frame and then
     * tests every visible cell against it.
     */
    fun bounds(): IntArray {
        val forward = startLine < endLine || (startLine == endLine && startCol <= endCol)
        return if (forward) {
            intArrayOf(startLine, startCol, endLine, endCol)
        } else {
            intArrayOf(endLine, endCol, startLine, startCol)
        }
    }

    /**
     * The selected text, one line per row with trailing blanks removed.
     * [lineAt] reads the buffer; the caller holds its lock.
     */
    fun text(cols: Int, lineAt: (Int) -> TermLine?): String {
        if (!active) return ""
        val b = bounds()
        val sl = b[0]
        val sc = b[1]
        val el = b[2]
        val ec = b[3]
        val sb = StringBuilder()
        for (li in sl..el) {
            val row = lineAt(li) ?: continue
            val from = if (li == sl) sc else 0
            val to = if (li == el) ec else cols - 1
            val line = StringBuilder()
            for (c in from..to.coerceAtMost(row.size - 1)) line.append(row[c].ch) // WIDE_TAIL is ""
            sb.append(line.trimEnd())
            if (li < el) sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        /** Whether the cell is inside [bounds] (from [bounds]). */
        fun contains(bounds: IntArray, line: Int, col: Int): Boolean {
            val sl = bounds[0]
            val el = bounds[2]
            return when {
                line < sl || line > el -> false
                line == sl && line == el -> col in bounds[1]..bounds[3]
                line == sl -> col >= bounds[1]
                line == el -> col <= bounds[3]
                else -> true
            }
        }
    }
}
