package hu.billman.konsolessh

import hu.billman.konsolessh.diagnostics.CrashRecorder

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
        CrashRecorder.install(container.crashFile)
    }

    /** Instrumented tests replace dependencies here, e.g. with fake sessions. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
