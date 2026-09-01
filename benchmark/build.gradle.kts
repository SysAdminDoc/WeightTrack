plugins {
    alias(libs.plugins.android.test)
}

/**
 * The long-history performance fixture.
 *
 * Its own module because a Macrobenchmark drives the app from outside its own process: the app
 * under test is started, stopped and started again, and nothing here may be linked into it.
 *
 * It runs against the `benchmark` build type, which is the release build with release signing
 * swapped for the debug key and profiling turned on. Measuring the debug build would measure the
 * debug build, which nobody installs.
 */
android {
    namespace = "com.weighttrack.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The app is built in two flavours and this measures one of them. Play is the one most
        // people install, and the difference between them is a barcode library that no screen
        // measured here ever touches.
        missingDimensionStrategy("distribution", "play")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        create("benchmark") {
            // The instrumentation APK, not the app. It has to be debuggable to be instrumented
            // at all; what must not be debuggable is the app under test, and that is set over in
            // the app's own build type.
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            // `:core` is a library with only debug and release. Without this, everything this
            // module pulls in through the app is asked for in a build type no library has.
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
