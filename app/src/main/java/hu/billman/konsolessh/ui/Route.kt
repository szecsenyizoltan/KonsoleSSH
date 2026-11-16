package hu.billman.konsolessh.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The app's screens. Serializable, so the back stack survives process death. */
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object Connections : Route

    @Serializable
    data object Sync : Route
}
