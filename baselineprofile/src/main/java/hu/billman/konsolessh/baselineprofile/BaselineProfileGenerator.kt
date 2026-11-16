package hu.billman.konsolessh.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the code that startup and the common screens run; see the module's build file. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        grantPermissions()
        pressHome()
        startActivityAndWait()
        dismissRestoreOffer()
        awaitMainScreen()
        commonScreens()
    }
}
