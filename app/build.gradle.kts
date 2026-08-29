import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.weighttrack"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.weighttrack"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
                val storePath = (props["storeFile"] as? String)?.trim()
                val storePass = (props["storePassword"] as? String)?.trim()
                val alias = (props["keyAlias"] as? String)?.trim()
                val keyPass = (props["keyPassword"] as? String)?.trim()
                if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
                    !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
                ) {
                    storeFile = rootProject.file(storePath)
                    storePassword = storePass
                    keyAlias = alias
                    keyPassword = keyPass
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Adds the accented and the long pseudo-languages. Switching the phone to one of
            // them shows at a glance which words still come from Kotlin rather than from the
            // resources, and whether a layout survives a language whose words run half again as
            // long as English.
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the debug key. A changed signing certificate blocks
            // in-place updates and strands everyone who already installed the app.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        // Google Play build. May use Play-services-backed pieces (ML Kit barcode later).
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "FOSS_ONLY", "false")
        }
        // F-Droid build. No proprietary dependencies, ever.
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "FOSS_ONLY", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // The exported schemas, so the migration tests can check an upgrade against the real
        // shape of each old version rather than against the current one.
        getByName("test") { assets.srcDirs("$projectDir/schemas") }
        getByName("androidTest") { assets.srcDirs("$projectDir/schemas") }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.okhttp)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Barcode reading is the one job with a genuinely better proprietary option and a
    // genuinely good free one, so each flavour gets the right one rather than the F-Droid
    // build going without.
    "playImplementation"(libs.mlkit.barcode.scanning)
    "fossImplementation"(libs.zxing.core)

    // Reading a barcode is tested against ones this generates, so the writer is a test
    // dependency in both flavours.
    testImplementation(libs.zxing.core)

    // The Wear Data Layer is Google Play services, so it exists only in the Play flavour.
    // The F-Droid build binds a no-op WearBridge instead and carries no Google dependency.
    "playImplementation"(libs.play.services.wearable)
    "playImplementation"(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.health.connect.client)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // The Health Connect fake, so the import can be driven across real page boundaries.
    testImplementation(libs.androidx.health.connect.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
