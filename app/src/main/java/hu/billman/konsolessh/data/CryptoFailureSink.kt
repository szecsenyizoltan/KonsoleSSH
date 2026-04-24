package hu.billman.konsolessh.data

import android.util.Log

/**
 * Globális hang a titkosítási anomáliák jelzésére.
 *
 * Eredetileg a [CryptoBoxConnectionRepository.save] csendesen loggolt,
 * ha az AES-GCM encrypt bukott, majd plaintext-fallbackkel ment tovább —
 * ez adatveszteség-szempontból biztonságos, de biztonsági szempontból nem.
 * A jelenlegi mechanizmus:
 *
 *  - Default handler: [Log.wtf] ("What a Terrible Failure") — Android
 *    minden hibajelző eszköze figyeli (logcat, Firebase Crashlytics, ADB
 *    bugreport, stb.), tehát a hiba nem sikkad el néma Log.e-ben.
 *  - Override-olható: a UI-oldal cserélheti a handler-t toast-tal,
 *    snackbar-ral vagy telemetriával (MainActivity.onCreate-ben pl.).
 *
 * Nincs külső dep, a típusok mind platform-sztenderdek.
 */
object CryptoFailureSink {

    /** A handler-t beállító extension-pont. Default: [Log.wtf]. */
    @Volatile
    var handler: (tag: String, message: String, cause: Throwable) -> Unit =
        { tag, message, cause -> Log.wtf(tag, message, cause) }

    fun report(tag: String, message: String, cause: Throwable) {
        handler(tag, message, cause)
    }
}
