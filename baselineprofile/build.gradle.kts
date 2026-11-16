plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

// Drives the release build of :app on a device to record which code runs at
// startup and in the common screens. The result is committed to :app as its
// baseline profile, so ART compiles that code ahead of time on install.
//
// Regenerate after larger UI changes, on the test emulator only (the run
// replaces and then uninstalls the app on the device it uses):
//   ANDROID_SERIAL=<test emulator> ./gradlew :app:generateBaselineProfile
// Measure the effect:
//   ANDROID_SERIAL=<test emulator> ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
android {
    namespace = "hu.billman.konsolessh.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The test emulator is the only device these run on; on an emulator the
        // timings are only good for comparing the runs with each other.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Same compiler JDK as :app.
kotlin {
    jvmToolchain(21)
}

baselineProfile {
    useConnectedDevices = true
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
