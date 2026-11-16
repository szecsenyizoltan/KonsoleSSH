package hu.billman.konsolessh.baselineprofile

import android.annotation.SuppressLint
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

const val PACKAGE = "hu.billman.konsolessh"
private const val TIMEOUT_MS = 10_000L
private const val SHORT_TIMEOUT_MS = 2_000L

/**
 * The app's own text for [name], in the device's language. The labels are
 * looked up in the installed app rather than written here, so the journey
 * works whatever language the device uses.
 */
@SuppressLint("DiscouragedApi")
private fun text(name: String): String {
    val resources = InstrumentationRegistry.getInstrumentation().context.packageManager
        .getResourcesForApplication(PACKAGE)
    return resources.getString(resources.getIdentifier(name, "string", PACKAGE))
}

/** Grants the runtime permissions, so their system dialog does not cover the app. */
fun MacrobenchmarkScope.grantPermissions() {
    listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK").forEach {
        device.executeShellCommand("pm grant $PACKAGE $it")
    }
}

/** On a device with nothing saved, the app offers a restore from Google Drive first. */
fun MacrobenchmarkScope.dismissRestoreOffer() {
    device.wait(Until.findObject(By.text(text("sync_offer_later"))), SHORT_TIMEOUT_MS)?.click()
}

/** Waits until the main screen, with its welcome page, is on screen. */
fun MacrobenchmarkScope.awaitMainScreen() {
    check(device.wait(Until.hasObject(By.desc(text("content_desc_new_tab"))), TIMEOUT_MS)) {
        "main screen not shown"
    }
}

// The element is clicked only once the screen is idle: found during an opening
// animation, it would be clicked where it was, not where it ends up.
private fun MacrobenchmarkScope.click(selector: BySelector, what: String) {
    check(device.wait(Until.hasObject(selector), TIMEOUT_MS)) { "no element $what" }
    device.waitForIdle()
    device.findObject(selector).click()
}

private fun MacrobenchmarkScope.clickDesc(name: String) = click(By.desc(text(name)), "described as $name")

private fun MacrobenchmarkScope.clickText(name: String) = click(By.text(text(name)), "labelled $name")

private fun MacrobenchmarkScope.back() {
    device.pressBack()
    device.waitForIdle()
}

/** The screens a user passes through most: the tab picker, the saved connections, the new connection form. */
fun MacrobenchmarkScope.commonScreens() {
    clickDesc("content_desc_new_tab")
    clickText("picker_back")
    awaitMainScreen()

    clickDesc("content_desc_more")
    clickText("manage_connections")
    clickDesc("content_desc_add_connection")
    device.waitForIdle()
    back()
    back()
    awaitMainScreen()
}
