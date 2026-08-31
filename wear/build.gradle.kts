import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The watch app.
 *
 * A separate APK sharing the phone's application id, which is how Wear OS 2 and later expect a
 * companion to be published. It is not embedded in the phone APK: that mechanism is for Wear 1
 * and would drag Google Play services into the F-Droid build.
 */
android {
    namespace = "com.weighttrack.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.weighttrack"
        // Wear OS 3. Earlier watches cannot run Compose for Wear at a usable frame rate.
        minSdk = 30
        targetSdk = 35
        versionCode = Versions.wearCode(project)
        versionName = Versions.name(project)
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
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // The watch APK has to carry the same certificate as the phone APK or Wear OS
            // will not treat them as one app.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)

    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.watchface.complications.data.source.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
