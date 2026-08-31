// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

/**
 * Refuses a release the phone and the watch cannot both be published from.
 *
 * Play treats the watch APK as a separate artifact of the same application id and rejects one
 * whose version code collides with the phone's. Both modules used to carry the literal 5. The
 * numbers now come from one place, and this reads what actually reached the manifests rather
 * than what the build files say, because the two are only the same until somebody overrides one.
 *
 * Run it against a built release: `./gradlew checkFormFactorVersions`.
 */
tasks.register("checkFormFactorVersions") {
    group = "verification"
    description = "Checks the phone and watch version codes cannot collide."

    // The packaged manifest is the one that goes into the APK, so it is the one worth reading.
    val phoneManifests = fileTree("app/build/intermediates/packaged_manifests") {
        include("**/AndroidManifest.xml")
    }
    val wearManifests = fileTree("wear/build/intermediates/packaged_manifests") {
        include("**/AndroidManifest.xml")
    }
    val expectedPhone = Versions.phoneCode(project)
    val expectedWear = Versions.wearCode(project)
    val expectedName = Versions.name(project)
    // Read at configuration time. Reaching for the project inside doLast is what the
    // configuration cache refuses, and this build has it switched on.
    val summary = Versions.summary(project)
    inputs.files(phoneManifests, wearManifests)

    doLast {
        check(expectedPhone != expectedWear) {
            "the phone and the watch would be published under version code $expectedPhone"
        }

        val problems = mutableListOf<String>()
        fun inspect(manifests: Iterable<File>, form: String, code: Int) {
            // Any flavour of release: the app builds playRelease and fossRelease, the watch a
            // plain release. A debug build carries a suffixed application id and is never
            // published, so holding it to the same rule would say nothing useful.
            val release = manifests.filter {
                Regex("(?i)[\\\\/][a-z]*release[\\\\/]").containsMatchIn(it.path)
            }
            if (release.isEmpty()) {
                problems += "no built release manifest for the $form, so nothing was checked"
                return
            }
            release.forEach { file ->
                val text = file.readText()
                val found = Regex("""android:versionCode="(\d+)"""").find(text)?.groupValues?.get(1)
                val name = Regex("""android:versionName="([^"]+)"""").find(text)?.groupValues?.get(1)
                if (found != code.toString()) {
                    problems += "${file.name} for the $form says version code $found, not $code"
                }
                if (name != null && !name.startsWith(expectedName)) {
                    problems += "${file.name} for the $form says version name $name, not $expectedName"
                }
            }
        }
        inspect(phoneManifests, "phone", expectedPhone)
        inspect(wearManifests, "watch", expectedWear)

        check(problems.isEmpty()) { problems.joinToString("\n") }
        logger.lifecycle("Release versions agree: $summary")
    }
}
