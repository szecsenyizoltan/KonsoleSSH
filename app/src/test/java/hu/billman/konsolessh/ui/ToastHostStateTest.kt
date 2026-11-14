package hu.billman.konsolessh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ToastHostStateTest {

    private val calls = mutableListOf<String>()

    @Test
    fun `closing an undo toast does not undo`() {
        val state = ToastHostState()
        state.showWithAction("uploaded", "Undo", 3000) { calls += "undo" }
        state.toasts.single().close()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the action of an undo toast runs when tapped`() {
        val state = ToastHostState()
        state.showWithAction("uploaded", "Undo", 3000) { calls += "undo" }
        state.toasts.single().act()
        assertEquals(listOf("undo"), calls)
    }

    @Test
    fun `a toast that resolves either way runs its action exactly once`() {
        val state = ToastHostState()
        state.showAutoAction("failed", "Close tab", 5000) { calls += "close" }
        val toast = state.toasts.single()
        toast.close()
        toast.timeOut()
        toast.act()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `waiting resolves a toast that resolves either way`() {
        val state = ToastHostState()
        state.showAutoAction("failed", "Close tab", 5000) { calls += "close" }
        state.toasts.single().timeOut()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun `several toasts are kept until removed`() {
        val state = ToastHostState()
        state.show("one")
        state.show("two")
        assertEquals(listOf("one", "two"), state.toasts.map { it.message })
        state.remove(state.toasts.first())
        assertEquals(listOf("two"), state.toasts.map { it.message })
    }
}
