import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "hu.billman.konsolessh"
    compileSdk = 37

    defaultConfig {
        applicationId = "hu.billman.konsolessh"
        minSdk = 26
        targetSdk = 37
        versionCode = 46
        versionName = "3.0.0"
        // Starts TestKonsoleApplication, which swaps in network-free sessions.
        testInstrumentationRunner = "hu.billman.konsolessh.KonsoleTestRunner"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric loads classes through its own class loader; without
            // this JaCoCo does not see the code those tests run.
            all {
                it.extensions.configure<JacocoTaskExtension> {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // JaCoCo for the JVM tests: ./gradlew :app:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

// A failing test prints its full exception, so a CI log shows the cause
// (for example the sshd log a server test failed with), not just its type.
tasks.withType<Test>().configureEach {
    // A hung test fails the build instead of running until the CI job is cancelled.
    timeout.set(Duration.ofMinutes(20))
    testLogging {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Compiles and runs the unit tests with JDK 21, the JDK Android Studio
// bundles, whatever JDK starts Gradle; foojay downloads it when missing.
// The bytecode stays at Java 17 (jvmTarget, compileOptions): on Android the
// available APIs come from minSdk and desugaring, not from the JDK.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Every resolved dependency version is pinned in gradle.lockfile, so a
// build cannot pick up a different transitive version unnoticed, and the
// CVE scan in CI has an exact list to check. After changing a dependency,
// both modules, since :baselineprofile resolves the app's release runtime:
// ./gradlew :app:dependencies :baselineprofile:dependencies --write-locks
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsch)
    // On Android, JSch sees an old Java version and picks the Bouncy Castle
    // implementations; without them there is no ssh-ed25519 host key and no
    // curve25519 key exchange.
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore)
    // Authorization for the Drive app data folder used by sync.
    implementation(libs.play.services.auth)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    // Installs the bundled baseline profile on devices where Play does not, such as sideloads.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Already pulled in by other AndroidX libraries; used directly for app foreground/background.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)

    // Unit tests (JVM + Robolectric for Android classes)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    // Writes JSON the way versions up to 1.1.30 did, for the compatibility tests;
    // also parses the terminal conformance fixtures.
    testImplementation(libs.gson)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests (device/emulator)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.mockk.android)
}
