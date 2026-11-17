package hu.billman.konsolessh.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ViewModel stores of the open dialogs, kept in an activity-scoped
 * ViewModel. A dialog's ViewModel must survive a configuration change like
 * the activity's, but unlike the activity's it must be cleared when the
 * dialog closes, so that work it started (a connection test, a key upload)
 * stops with it and the next opening starts empty.
 */
internal class DialogStores : ViewModel() {

    private val stores = mutableMapOf<String, ViewModelStore>()

    fun get(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * The ViewModel scope of the dialog identified by [key]: it lives while the
 * caller stays composed, across configuration changes, and is cleared when
 * the caller leaves the composition for good.
 */
@Composable
internal fun rememberDialogScope(key: String): ViewModelStoreOwner {
    val activity = LocalActivity.current
    val stores = viewModel<DialogStores>()
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = stores.get(key)
        }
    }
    DisposableEffect(key) {
        onDispose { if (activity?.isChangingConfigurations != true) stores.clear(key) }
    }
    return owner
}
