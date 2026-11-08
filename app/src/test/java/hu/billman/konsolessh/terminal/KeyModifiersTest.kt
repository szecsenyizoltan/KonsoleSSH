package hu.billman.konsolessh.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyModifiersTest {

    private fun state(vararg m: Modifier) = ModifierState(m.toSet())

    @Test
    fun `no modifier leaves input untouched`() {
        assertArrayEquals("ls".toByteArray(), ModifierState().applyTo("ls".toByteArray()))
    }

    @Test
    fun `ctrl maps letters, space and bracket to control codes`() {
        assertArrayEquals(byteArrayOf(3), state(Modifier.CTRL).applyTo("c".toByteArray()))
        assertArrayEquals(byteArrayOf(3), state(Modifier.CTRL).applyTo("C".toByteArray()))
        assertArrayEquals(byteArrayOf(0), state(Modifier.CTRL).applyTo(" ".toByteArray()))
        assertArrayEquals(byteArrayOf(27), state(Modifier.CTRL).applyTo("[".toByteArray()))
    }

    @Test
    fun `shift uppercases a single letter and alt prefixes ESC`() {
        assertArrayEquals("A".toByteArray(), state(Modifier.SHIFT).applyTo("a".toByteArray()))
        assertArrayEquals(byteArrayOf(27, 'x'.code.toByte()), state(Modifier.ALT).applyTo("x".toByteArray()))
        assertArrayEquals(
            byteArrayOf(27, 24),
            state(Modifier.CTRL, Modifier.ALTGR).applyTo("x".toByteArray()),
        )
    }

    @Test
    fun `consume applies once and resets`() {
        val modifiers = KeyModifiers()
        modifiers.toggle(Modifier.CTRL)
        assertArrayEquals(byteArrayOf(3), modifiers.consume("c".toByteArray()))
        assertArrayEquals("c".toByteArray(), modifiers.consume("c".toByteArray()))
        assertTrue(modifiers.state.value.active.isEmpty())
    }

    @Test
    fun `toggle twice switches the modifier off`() {
        val modifiers = KeyModifiers()
        modifiers.toggle(Modifier.ALT)
        modifiers.toggle(Modifier.ALT)
        assertTrue(modifiers.state.value.active.isEmpty())
    }
}
