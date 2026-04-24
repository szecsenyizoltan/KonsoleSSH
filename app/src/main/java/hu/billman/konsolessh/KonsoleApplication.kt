package hu.billman.konsolessh

/**
 * Application-alosztály az [AppContainer] (manuális dependency-gráf)
 * konstrukciójához.
 *
 * A Hilt-bevezetés helyett szándékosan egy "Now in Android"-szerű vékony
 * manuális container-pattern — ennyi komplexitású projektnél a hordozott
 * annotation-processing egyáltalán nem indokolt.
 */
class KonsoleApplication : android.app.Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
