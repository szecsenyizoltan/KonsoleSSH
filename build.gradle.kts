plugins {
    alias(libs.plugins.android.application) apply false
    // Pins the Kotlin Gradle plugin version that AGP's built-in Kotlin uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

dependencies {
    // ktlint's rules (indentation, wildcard imports, wrapping, ...) run inside
    // detekt, so `./gradlew detekt` also checks formatting; there is no
    // separate ktlint task.
    detektPlugins(libs.detekt.formatting)
}

// ── detekt: Kotlin static analysis ───────────────────────────────────────────
// One root task (`./gradlew detekt`) analyses all Kotlin sources of the app
// module. The config only lists differences from detekt's defaults; issues
// that existed when it was introduced are in detekt-baseline.xml, and new
// code is held to the full rules.

detekt {
    toolVersion = libs.versions.detekt.get()
    source.setFrom(files("app/src/main/java", "app/src/test/java", "app/src/androidTest/java"))
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/detekt-baseline.xml")
    buildUponDefaultConfig = true
    autoCorrect = false
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
    jvmTarget = "17"
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}
