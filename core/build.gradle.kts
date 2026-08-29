plugins {
    alias(libs.plugins.android.library)
}

/**
 * The trend, goal, body and unit maths, plus the domain types they work on.
 *
 * Pure Kotlin with no Android imports and no dependency on the rest of the app. It sits in its
 * own module because the watch has to render weights in the same unit, to the same precision,
 * as the phone: a second copy of the rounding rules on the watch would drift.
 */
android {
    namespace = "com.weighttrack.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
