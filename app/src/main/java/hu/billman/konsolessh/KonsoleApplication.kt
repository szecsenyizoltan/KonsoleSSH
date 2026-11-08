package hu.billman.konsolessh

import hu.billman.konsolessh.diagnostics.CrashRecorder

/**
 * Application-alosztály az [AppContainer] (manuális dependency-gráf)
 * konstrukciójához.
 *
 * A Hilt-bevezetés helyett szándékosan egy "Now in Android"-szerű vékony
 * manuális container-pattern — ennyi komplexitású projektnél a hordozott
 * annotation-processing egyáltalán nem indokolt.
 */
open class KonsoleApplication : android.app.Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        CrashRecorder.install(container.crashFile)
    }

    /** Az instrumentált tesztek itt cserélnek függőséget (hamis munkamenetek). */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
