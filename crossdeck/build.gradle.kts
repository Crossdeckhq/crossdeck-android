// :crossdeck library module — the published artefact.
//
// Floor: minSdk 21 (Android 5.0). Lower would require avoiding
// coroutines/Flow, which would gut the actor-equivalent model.
// 21+ is now ~99% of active devices; the cut is fine.
//
// One runtime dep: kotlinx-coroutines. Android idiom — every modern
// Android library uses it (including AndroidX). HTTP goes through
// java.net.HttpURLConnection (zero-dep, on the platform); JSON
// through org.json (also on the platform). No OkHttp / Moshi /
// kotlinx-serialization — consumers don't inherit our version pin.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.crossdeck"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Strict-API forces every public class to declare visibility
        // explicitly — no accidental `public class Foo` slipping in.
        // Mirrors the Swift SDK's strict-concurrency stance.
        freeCompilerArgs += listOf(
            "-Xexplicit-api=strict",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }

    buildFeatures {
        // No view binding / data binding / compose — we're a
        // headless analytics library.
        buildConfig = false
    }

    testOptions {
        // Pure JVM unit tests — no need for Robolectric for the
        // bulk of bank-grade behaviour coverage. The few Android
        // framework dependencies (SharedPreferences, Context) are
        // hidden behind interfaces we test with fakes.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The single runtime dependency. AndroidX libs (lifecycle-process)
    // bring kotlinx-coroutines along anyway; declaring it explicitly
    // pins the version the SDK is tested against.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX lifecycle — auto-flush on app-background. Two tiny
    // jars; same dependency RN's autolinking pulls implicitly on
    // an RN host app.
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.7.0")

    // Test surface — pure-JVM unit tests, no Android framework.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// Publication block — Maven Central / GitHub Packages later. The
// shape mirrors the Swift Package.swift product declaration.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.crossdeck"
            artifactId = "crossdeck"
            version = "1.4.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Crossdeck Android SDK")
                description.set(
                    "Verified subscriptions, entitlements, error capture, " +
                    "and product telemetry on Android — bank-grade native client.",
                )
                url.set("https://github.com/VistaApps-za/crossdeck-android")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
