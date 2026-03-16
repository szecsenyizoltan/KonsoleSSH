package hu.szecsenyi.konsolessh.terminal

import android.graphics.Color
import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric unit tests for AnsiParser.
 * Tests ANSI escape sequence parsing → Android Spannable conversion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnsiParserTest {

    private val ESC = "\u001B"

    // ---- Plain text (no escapes) ----

    @Test
    fun plainText_noEscapes_passthroughWithoutSpans() {
        val result = AnsiParser.parse("Hello, World!")
        assertEquals("Hello, World!", result.toString())
        assertEquals(0, result.getSpans(0, result.length, Any::class.java).size)
    }

    @Test
    fun emptyString_returnsEmptySpannable() {
        val result = AnsiParser.parse("")
        assertEquals("", result.toString())
    }

    @Test
    fun multilinePlainText_isPreserved() {
        val input = "line1\nline2\nline3"
        val result = AnsiParser.parse(input)
        assertEquals(input, result.toString())
    }

    // ---- SGR reset ----

    @Test
    fun sgrReset_code0_stripsEscapeAndPreservesText() {
        val input = "${ESC}[0mHello"
        val result = AnsiParser.parse(input)
        assertEquals("Hello", result.toString())
    }

    @Test
    fun sgrReset_bareCode_stripsEscapeAndPreservesText() {
        val input = "${ESC}[mWorld"
        val result = AnsiParser.parse(input)
        assertEquals("World", result.toString())
    }

    // ---- Foreground colors (30–37) ----

    @Test
    fun fgRed_code31_appliesForegroundColorSpan() {
        val input = "${ESC}[31mError"
        val result = AnsiParser.parse(input)
        assertEquals("Error", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(170, 0, 0), spans[0].foregroundColor)
    }

    @Test
    fun fgGreen_code32_appliesCorrectColor() {
        val input = "${ESC}[32mOK"
        val result = AnsiParser.parse(input)
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(0, 170, 0), spans[0].foregroundColor)
    }

    @Test
    fun fgReset_code39_removesColorFromSubsequentText() {
        val input = "${ESC}[31mRed${ESC}[39mNormal"
        val result = AnsiParser.parse(input)
        assertEquals("RedNormal", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        // Only "Red" (0..3) should have a span, not "Normal"
        assertEquals(1, spans.size)
        assertEquals(0, result.getSpanStart(spans[0]))
        assertEquals(3, result.getSpanEnd(spans[0]))
    }

    // ---- Background colors (40–47) ----

    @Test
    fun bgBlue_code44_appliesBackgroundColorSpan() {
        val input = "${ESC}[44mBlue BG"
        val result = AnsiParser.parse(input)
        assertEquals("Blue BG", result.toString())
        val spans = result.getSpans(0, result.length, BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(0, 0, 170), spans[0].backgroundColor)
    }

    @Test
    fun bgReset_code49_removesBgFromSubsequentText() {
        val input = "${ESC}[41mBG${ESC}[49mNoBG"
        val result = AnsiParser.parse(input)
        assertEquals("BGNoBG", result.toString())
        val spans = result.getSpans(0, result.length, BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(0, result.getSpanStart(spans[0]))
        assertEquals(2, result.getSpanEnd(spans[0]))
    }

    // ---- Bright colors (90–97 fg, 100–107 bg) ----

    @Test
    fun brightRed_code91_appliesBrightColor() {
        val input = "${ESC}[91mBright"
        val result = AnsiParser.parse(input)
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(255, 85, 85), spans[0].foregroundColor)
    }

    @Test
    fun brightWhiteBg_code107_appliesBrightColor() {
        val input = "${ESC}[107mBrightBG"
        val result = AnsiParser.parse(input)
        val spans = result.getSpans(0, result.length, BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(255, 255, 255), spans[0].backgroundColor)
    }

    // ---- Bold ----

    @Test
    fun bold_code1_appliesStyleSpanBold() {
        val input = "${ESC}[1mBold text"
        val result = AnsiParser.parse(input)
        assertEquals("Bold text", result.toString())
        val spans = result.getSpans(0, result.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Typeface.BOLD, spans[0].style)
    }

    @Test
    fun boldOff_code22_stopsBoldSpan() {
        val input = "${ESC}[1mBold${ESC}[22mNormal"
        val result = AnsiParser.parse(input)
        assertEquals("BoldNormal", result.toString())
        val spans = result.getSpans(0, result.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(0, result.getSpanStart(spans[0]))
        assertEquals(4, result.getSpanEnd(spans[0]))
    }

    // ---- Combined SGR params ----

    @Test
    fun combinedBoldAndRed_appliesBothSpans() {
        val input = "${ESC}[1;31mBoldRed"
        val result = AnsiParser.parse(input)
        assertEquals("BoldRed", result.toString())
        val bold = result.getSpans(0, result.length, StyleSpan::class.java)
        val fg = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, bold.size)
        assertEquals(Typeface.BOLD, bold[0].style)
        assertEquals(1, fg.size)
        assertEquals(Color.rgb(170, 0, 0), fg[0].foregroundColor)
    }

    // ---- SGR full reset clears all ----

    @Test
    fun sgrResetAfterColor_clearsSpanForSubsequentText() {
        val input = "${ESC}[32mGreen${ESC}[0mPlain"
        val result = AnsiParser.parse(input)
        assertEquals("GreenPlain", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(0, result.getSpanStart(spans[0]))
        assertEquals(5, result.getSpanEnd(spans[0]))
    }

    // ---- 256-color (SGR 38;5;N) ----

    @Test
    fun color256_fgIndex0_isBlack() {
        val input = "${ESC}[38;5;0mBlack"
        val result = AnsiParser.parse(input)
        assertEquals("Black", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(0, 0, 0), spans[0].foregroundColor)
    }

    @Test
    fun color256_fgIndex196_isBrightRedCube() {
        val input = "${ESC}[38;5;196mRed256"
        val result = AnsiParser.parse(input)
        assertEquals("Red256", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        // Index 196 = 16 + 5*36 = 196 → r=5,g=0,b=0 → rgb(255,0,0)
        assertEquals(Color.rgb(255, 0, 0), spans[0].foregroundColor)
    }

    @Test
    fun color256_bgIndex_appliesBackgroundColorSpan() {
        val input = "${ESC}[48;5;21mBlueBG"
        val result = AnsiParser.parse(input)
        assertEquals("BlueBG", result.toString())
        val spans = result.getSpans(0, result.length, BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
    }

    @Test
    fun color256_grayscaleIndex232_isDarkestGray() {
        val input = "${ESC}[38;5;232mDarkGray"
        val result = AnsiParser.parse(input)
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        // Index 232 = first grayscale: v = 8+0*10 = 8
        assertEquals(Color.rgb(8, 8, 8), spans[0].foregroundColor)
    }

    // ---- Truecolor (SGR 38;2;R;G;B) ----

    @Test
    fun truecolorFg_appliesExactRgb() {
        val input = "${ESC}[38;2;255;128;0mOrange"
        val result = AnsiParser.parse(input)
        assertEquals("Orange", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(255, 128, 0), spans[0].foregroundColor)
    }

    @Test
    fun truecolorBg_appliesExactRgb() {
        val input = "${ESC}[48;2;0;0;128mNavyBG"
        val result = AnsiParser.parse(input)
        val spans = result.getSpans(0, result.length, BackgroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Color.rgb(0, 0, 128), spans[0].backgroundColor)
    }

    // ---- Cursor movement and other CSI sequences are stripped ----

    @Test
    fun cursorUp_isStrippedAndTextPreserved() {
        val input = "before${ESC}[Aafter"
        val result = AnsiParser.parse(input)
        assertEquals("beforeafter", result.toString())
    }

    @Test
    fun eraseLine_isStripped() {
        val input = "text${ESC}[2Kmore"
        val result = AnsiParser.parse(input)
        assertEquals("textmore", result.toString())
    }

    @Test
    fun cursorPosition_isStripped() {
        val input = "${ESC}[10;20Hcontent"
        val result = AnsiParser.parse(input)
        assertEquals("content", result.toString())
    }

    // ---- Multiple segments ----

    @Test
    fun sequenceOfColoredWords_eachGetOwnSpan() {
        val input = "${ESC}[31mRed${ESC}[32mGreen${ESC}[34mBlue"
        val result = AnsiParser.parse(input)
        assertEquals("RedGreenBlue", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals(3, spans.size)
    }

    @Test
    fun plainTextBetweenEscapes_hasNoSpans() {
        val input = "${ESC}[31mRed${ESC}[0m plain ${ESC}[32mGreen"
        val result = AnsiParser.parse(input)
        assertEquals("Red plain Green", result.toString())
        // " plain " (positions 3..10) should have no FG span
        val spans = result.getSpans(3, 10, ForegroundColorSpan::class.java)
        assertEquals(0, spans.size)
    }

    // ---- Edge cases ----

    @Test
    fun loneEscAtEndOfString_doesNotCrash() {
        val input = "text$ESC"
        val result = AnsiParser.parse(input)
        assertEquals("text", result.toString())
    }

    @Test
    fun incompleteCsiAtEnd_doesNotCrash() {
        val input = "text${ESC}["
        val result = AnsiParser.parse(input)
        assertTrue(result.toString().startsWith("text"))
    }

    @Test
    fun unknownSgrParam_isSilentlyIgnored() {
        // Code 99 is not defined — must not crash, text still appears
        val input = "${ESC}[99mtext"
        val result = AnsiParser.parse(input)
        assertEquals("text", result.toString())
    }

    @Test
    fun color256_missingIndex_doesNotCrash() {
        val input = "${ESC}[38;5mtext"  // missing the actual index number
        val result = AnsiParser.parse(input)
        assertEquals("text", result.toString())
    }

    @Test
    fun truecolor_incompleteParams_doesNotCrash() {
        val input = "${ESC}[38;2;255;0mtext"  // only R;G, missing B
        val result = AnsiParser.parse(input)
        assertEquals("text", result.toString())
    }

    @Test
    fun nullByteInStream_doesNotCrash() {
        val input = "before\u0000after"
        val result = AnsiParser.parse(input)
        assertEquals("before\u0000after", result.toString())
    }
}
