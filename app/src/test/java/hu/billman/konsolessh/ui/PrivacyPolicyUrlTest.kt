package hu.billman.konsolessh.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import hu.billman.konsolessh.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The in-app policy link must open the page in the app's language on the official site. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivacyPolicyUrlTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private fun urlFor(language: String): String {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return app.createConfigurationContext(config).getString(R.string.privacy_policy_url)
    }

    @Test
    fun `every app language opens its own policy page`() {
        val expected = mapOf(
            "en" to "en/",
            "hu" to "",
            "de" to "de/",
            "es" to "es/",
            "fr" to "fr/",
            "sk" to "sk/",
            "ro" to "ro/",
        )
        expected.forEach { (language, path) ->
            assertEquals(language, "https://konsolessh.billman.hu/${path}privacy/", urlFor(language))
        }
    }

    @Test
    fun `a language the app does not have falls back to the English page`() {
        assertEquals("https://konsolessh.billman.hu/en/privacy/", urlFor("it"))
    }
}
