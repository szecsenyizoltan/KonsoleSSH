plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.detekt)
}

// ── detekt: Kotlin static analysis ───────────────────────────────────────────
// Egyetlen gyökér-Task (`./gradlew detekt`), ami az app/ modul összes Kotlin
// forrását elemzi. A "build upon default config" minta — a jelen állapot
// baseline-ban (detekt-baseline.xml), a jövőbeli kódra szigorú.

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
