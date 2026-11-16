package hu.billman.konsolessh

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Creates the [AppContainer], the hand-written dependency graph.
 *
 * Manual DI instead of Hilt is deliberate: for an app of this size, an
 * annotation processor and its build cost buy nothing.
 */
open class KonsoleApplication : android.app.Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        container.deleteLeftovers()
        // The whole app, not one activity: switching between its screens is neither.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.onAppForeground()

                override fun onStop(owner: LifecycleOwner) = container.onAppBackground()
            },
        )
    }

    /** Instrumented tests replace dependencies here, e.g. with fake sessions. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
