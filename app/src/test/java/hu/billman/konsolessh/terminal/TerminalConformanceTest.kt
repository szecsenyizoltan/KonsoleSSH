package hu.billman.konsolessh.terminal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.fail
import org.junit.Test

/**
 * Compares against a reference terminal. tools/terminal-conformance/record.py
 * records the fixtures: the output of real programs (bash, vim, less, htop,
 * mc) in a tmux pane, and at each checkpoint what tmux shows. Here the same
 * bytes go into the [TerminalBuffer], and the screen must match in every
 * character, colour, attribute and the cursor.
 */
class TerminalConformanceTest {

    private data class Cell(val ch: String, val fg: TermColor, val bg: TermColor, val flags: Set<String>)

    @Test fun `printf basics`() = replay("printf-basics")

    @Test fun `bash line editing`() = replay("bash-line-editing")

    @Test fun `vim with resize`() = replay("vim")

    @Test fun less() = replay("less")

    @Test fun htop() = replay("htop")

    @Test fun `mc with utf-8 frames`() = replay("mc-utf8")

    @Test fun `mc with dec line drawing`() = replay("mc-acs")

    private fun replay(name: String) {
        val bytes = resource("$name.bin").readBytes()
        val fixture = Json.parseToJsonElement(resource("$name.json").readText()).jsonObject
        val buf = TerminalBuffer(fixture.int("cols"), fixture.int("rows"))
        val problems = mutableListOf<String>()
        var pos = 0
        for (cp in fixture.getValue("checkpoints").jsonArray.map { it.jsonObject }) {
            val offset = cp.int("offset")
            if (offset > pos) buf.write(bytes.copyOfRange(pos, offset), respond = false)
            pos = offset
            when (cp.string("type")) {
                "resize" -> buf.resize(cp.int("cols"), cp.int("rows"))
                else -> problems += compare(buf, cp).map { "[${cp.string("label")}] $it" }
            }
        }
        if (problems.isNotEmpty()) {
            fail("$name: ${problems.size} difference(s)\n" + problems.take(MAX_REPORTED).joinToString("\n"))
        }
    }

    private fun compare(buf: TerminalBuffer, cp: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        val (x, y) = cp.getValue("cursor").jsonArray.map { it.jsonPrimitive.int }
        val col = minOf(buf.cursorCol, buf.cols - 1)
        if (col != x || buf.cursorRow != y) problems += "cursor ${buf.cursorRow},$col, expected $y,$x"
        val alternate = cp.getValue("alternate").jsonPrimitive.boolean
        if (buf.altScreenActive != alternate) {
            problems += "alternate screen ${buf.altScreenActive}, expected $alternate"
        }
        cp.getValue("screen").jsonArray.forEachIndexed { r, row ->
            val expected = row.jsonArray.map(::expectedCell)
            val actual = actualRow(buf, r)
            val text = { cells: List<Cell> -> cells.joinToString("") { it.ch } }
            if (text(expected) != text(actual)) {
                problems += "row $r text\n  expected: «${text(expected)}»\n  actual:   «${text(actual)}»"
            } else {
                expected.zip(actual).forEachIndexed { i, (e, a) ->
                    val diff = styleDiff(e, a)
                    if (diff != null) problems += "row $r char $i '${e.ch}': $diff"
                }
            }
        }
        return problems
    }

    // Foreground and text attributes matter only where something is drawn.
    private fun styleDiff(e: Cell, a: Cell): String? = when {
        e.bg != a.bg -> "bg ${hex(a.bg)}, expected ${hex(e.bg)}"
        e.ch.isNotBlank() && e.fg != a.fg -> "fg ${hex(a.fg)}, expected ${hex(e.fg)}"
        e.ch.isNotBlank() && e.flags != a.flags -> "attributes ${a.flags}, expected ${e.flags}"
        e.ch.isBlank() && ("reverse" in e.flags) != ("reverse" in a.flags) -> "reverse ${"reverse" in a.flags}"
        else -> null
    }

    private fun actualRow(buf: TerminalBuffer, r: Int): List<Cell> {
        val line = buf.lineAt(buf.scrollbackSize + r) ?: return emptyList()
        val cells = line.filter { it.ch != TerminalBuffer.WIDE_TAIL }.map { c ->
            val flags = buildSet {
                if (c.bold) add("bold")
                if (c.dim) add("dim")
                if (c.italic) add("italic")
                if (c.underline) add("underline")
                if (c.reverse) add("reverse")
                if (c.strikethrough) add("strike")
            }
            Cell(c.ch, c.fg, c.bg, flags)
        }.toMutableList()
        while (cells.isNotEmpty() && cells.last().isDefaultBlank()) cells.removeAt(cells.lastIndex)
        return cells
    }

    // Same rule as the recorder: trailing blanks in the default colours are not compared.
    private fun Cell.isDefaultBlank() = ch == " " && bg == TermColor.DEFAULT_BG && "reverse" !in flags

    private fun expectedCell(json: JsonElement): Cell {
        val a = json.jsonArray
        return Cell(
            a[0].jsonPrimitive.content,
            color(a[1], TermColor.DEFAULT_FG),
            color(a[2], TermColor.DEFAULT_BG),
            a[3].jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    private fun color(json: JsonElement, default: TermColor): TermColor = when {
        json is JsonNull -> default
        !json.jsonPrimitive.isString -> SgrPalette.xterm256(json.jsonPrimitive.int)
        else -> json.jsonPrimitive.content.removePrefix("#").toInt(HEX).let {
            TermColor.rgb(it shr BYTE_2 and BYTE_MASK, it shr BYTE_1 and BYTE_MASK, it and BYTE_MASK)
        }
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private fun hex(c: TermColor) = "#%06x".format(c.argb and RGB_MASK)

    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader?.getResource("terminal-conformance/$name")) { "missing fixture $name" }

    private companion object {
        const val MAX_REPORTED = 40
        const val HEX = 16
        const val BYTE_1 = 8
        const val BYTE_2 = 16
        const val BYTE_MASK = 0xFF
        const val RGB_MASK = 0xFFFFFF
    }
}
