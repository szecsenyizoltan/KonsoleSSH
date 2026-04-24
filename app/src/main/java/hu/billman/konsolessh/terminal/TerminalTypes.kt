package hu.billman.konsolessh.terminal

/**
 * Platformfüggetlen terminál-adattípusok a TerminalBuffer-hez.
 *
 * A Buffer pure-JVM logika (nincs android.graphics.* hívás), ezért a színek
 * ARGB-kódolású Int-ek, nem Android Color objektumok. A renderelő oldal
 * (TerminalView) közvetlenül át tudja adni a `TermColor.argb`-t a Paint-nek.
 */

/**
 * ARGB formátumú szín. Bit-mintázata megegyezik az android.graphics.Color-éval,
 * így a renderer ezt a `paint.color` értékére dobhatja transzformáció nélkül.
 */
@JvmInline
value class TermColor(val argb: Int) {
    companion object {
        val DEFAULT_FG = TermColor(0xFFCCCCCC.toInt())
        val DEFAULT_BG = TermColor(0xFF000000.toInt())
    }
}

/**
 * A rácscella mutable — a Buffer in-place frissít, ami nagy scrollback mellett
 * érdemi allokáció-megtakarítás. Az osztályon kívülről olvasóként kezelendő.
 */
data class TermCell(
    var ch: String = " ",
    var fg: TermColor = TermColor.DEFAULT_FG,
    var bg: TermColor = TermColor.DEFAULT_BG,
    var bold: Boolean = false,
    var underline: Boolean = false,
    var reverse: Boolean = false,
) {
    companion object {
        /**
         * Minden hívás új, üres cellát ad vissza (a konstans lehetne shared singleton,
         * de a Buffer in-place írja a cellákat, ezért külön példány kell).
         */
        fun blank() = TermCell()
    }
}

/** Egy logikai sor (scrollback vagy élő képernyő). */
typealias TermLine = Array<TermCell>

/** Terminál-méret cellaszámban. */
data class TermSize(val cols: Int, val rows: Int)

/** Kurzor-pozíció 0-alapon (a CSI 1-alapú címzést a Buffer fordítja le). */
data class CursorPos(val row: Int, val col: Int)

/**
 * Immutable snapshot a renderernek. A rendering minden keretben lekér egy friss
 * példányt (olcsó: csak elemi értékek), így a View nem tart referenciát a Buffer
 * belső állapotára.
 */
data class TerminalSnapshot(
    val size: TermSize,
    val scrollbackSize: Int,
    val cursor: CursorPos,
    val cursorHidden: Boolean,
    val altScreenActive: Boolean,
    val appCursorKeys: Boolean,
    val bracketedPasteMode: Boolean,
)
