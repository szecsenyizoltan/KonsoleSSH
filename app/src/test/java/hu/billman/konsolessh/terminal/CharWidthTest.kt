package hu.billman.konsolessh.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class CharWidthTest {

    private fun width(s: String) = CharWidth.of(s.codePointAt(0))

    @Test
    fun `narrow, wide and zero width code points`() {
        assertEquals(1, width("a"))
        assertEquals(1, width("ő"))
        assertEquals(1, width("─"))
        assertEquals(2, width("中"))
        assertEquals(2, width("한"))
        assertEquals(2, width("🔥"))
        assertEquals(2, width("✅"))
        assertEquals(0, width("́"))
        assertEquals(0, width("️"))
    }

    @Test
    fun `range tables are sorted and well formed`() {
        listOf("ZERO", "WIDE").forEach { name ->
            val field = CharWidth::class.java.getDeclaredField(name).apply { isAccessible = true }
            val ranges = field.get(CharWidth) as IntArray
            assertEquals(name, 0, ranges.size % 2)
            for (i in ranges.indices step 2) {
                assert(ranges[i] <= ranges[i + 1]) { "$name[$i]" }
                if (i > 0) assert(ranges[i - 1] < ranges[i]) { "$name[$i] overlaps" }
            }
        }
    }
}
