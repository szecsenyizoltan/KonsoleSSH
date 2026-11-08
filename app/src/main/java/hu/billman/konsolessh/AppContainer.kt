package hu.billman.konsolessh

import android.content.Context
import hu.billman.konsolessh.data.AesGcmKeystoreBox
import hu.billman.konsolessh.data.ConnectionRepository
import hu.billman.konsolessh.data.CryptoBox
import hu.billman.konsolessh.data.CryptoBoxConnectionRepository
import hu.billman.konsolessh.ssh.HostKeyStore
import java.io.File

/**
 * Manuális dependency-gráf. Egyetlen példány él az Application onCreate()-je
 * alatt, Activity-k és Fragmentek a Context-en át `(application as
 * KonsoleApplication).container` formában érik el.
 *
 * ViewModel-oldalt [KonsoleViewModelFactory] fogja hídolni; a repository-kat
 * konstruktor-injektáljuk.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // Megosztott kriptó-impl: a Keystore-alias egyszeri generálása után minden
    // kapcsolat ugyanezzel a kulccsal kódolódik. Lazy, hogy első használatig
    // ne érintsük a Keystore-t (teszt/Robolectric idegenkedése miatt).
    val cryptoBox: CryptoBox by lazy { AesGcmKeystoreBox() }

    val connectionRepository: ConnectionRepository by lazy {
        CryptoBoxConnectionRepository(appContext, cryptoBox)
    }

    // Egyetlen példány: a párhuzamos fülek így nem írják felül egymás bejegyzéseit.
    val hostKeyStore: HostKeyStore by lazy {
        HostKeyStore(File(appContext.filesDir, "known_hosts"))
    }

    companion object {
        fun from(context: Context): AppContainer =
            (context.applicationContext as KonsoleApplication).container
    }
}
