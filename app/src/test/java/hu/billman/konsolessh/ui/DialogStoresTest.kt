package hu.billman.konsolessh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogStoresTest {

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private fun probeIn(store: ViewModelStore): Probe = ViewModelProvider.create(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
        },
    )[Probe::class]

    @Test
    fun `a dialog finds its ViewModel again until the dialog is cleared`() {
        val stores = DialogStores()
        val first = probeIn(stores.get("form"))
        assertSame(first, probeIn(stores.get("form")))

        stores.clear("form")

        assertTrue(first.cleared)
        assertNotSame(first, probeIn(stores.get("form")))
    }

    @Test
    fun `clearing one dialog leaves the others`() {
        val stores = DialogStores()
        val form = probeIn(stores.get("form"))
        val picker = probeIn(stores.get("picker"))

        stores.clear("form")

        assertTrue(form.cleared)
        assertFalse(picker.cleared)
    }

    @Test
    fun `clearing the activity's ViewModel clears every dialog`() {
        val activityStore = ViewModelStore()
        val stores = ViewModelProvider.create(activityStore)[DialogStores::class]
        val form = probeIn(stores.get("form"))
        val picker = probeIn(stores.get("picker"))

        activityStore.clear()

        assertTrue(form.cleared)
        assertTrue(picker.cleared)
    }
}
