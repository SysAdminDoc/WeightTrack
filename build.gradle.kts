// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
    }

    buildscript.configurations.matching { it.name == "classpath" }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
}

/**
 * Refuses a release the phone and the watch cannot both be published from.
 *
 * Play treats the watch APK as a separate artifact of the same application id and rejects one
 * whose version code collides with the phone's. Both modules used to carry the literal 5. The
 * numbers now come from one place, and this reads what actually reached the manifests rather
 * than what the build files say, because the two are only the same until somebody overrides one.
 *
 * Run it directly: `./gradlew checkFormFactorVersions` generates the manifests it inspects.
 */
tasks.register("checkFormFactorVersions") {
    group = "verification"
    description = "Checks the phone and watch version codes cannot collide."
    dependsOn(
        ":app:processPlayReleaseManifestForPackage",
        ":app:processFossReleaseManifestForPackage",
        ":wear:processReleaseManifestForPackage",
    )

    // The packaged manifest is the one that goes into the APK, so it is the one worth reading.
    // Keep these inputs exact. Declaring the whole parent directories also claims debug manifests
    // produced by a combined test-and-release run and creates undeclared task dependencies.
    val phoneManifests = listOf(
        file(
            "app/build/intermediates/packaged_manifests/playRelease/" +
                "processPlayReleaseManifestForPackage/AndroidManifest.xml",
        ),
        file(
            "app/build/intermediates/packaged_manifests/fossRelease/" +
                "processFossReleaseManifestForPackage/AndroidManifest.xml",
        ),
    )
    val wearManifests = listOf(
        file(
            "wear/build/intermediates/packaged_manifests/release/" +
                "processReleaseManifestForPackage/AndroidManifest.xml",
        ),
    )
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
        fun inspect(
            manifests: Iterable<File>,
            form: String,
            code: Int,
            requiredVariants: Set<String>,
        ) {
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
            requiredVariants.forEach { variant ->
                val marker = "/${variant.lowercase()}/"
                if (release.none { marker in it.invariantSeparatorsPath.lowercase() }) {
                    problems += "no built $variant manifest for the $form"
                }
            }
            release.forEach { file ->
                problems += ReleaseManifestVersion.problems(
                    text = file.readText(),
                    fileName = file.name,
                    form = form,
                    expectedCode = code,
                    expectedName = expectedName,
                )
            }
        }
        inspect(phoneManifests, "phone", expectedPhone, setOf("playRelease", "fossRelease"))
        inspect(wearManifests, "watch", expectedWear, setOf("release"))

        check(problems.isEmpty()) { problems.joinToString("\n") }
        logger.lifecycle("Release versions agree: $summary")
    }
}
