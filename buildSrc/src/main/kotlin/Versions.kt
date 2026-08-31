import org.gradle.api.Project

/**
 * The one place the phone and the watch agree about what release they are.
 *
 * Both modules used to carry their own literals, and both said version code 5. Play rejects a
 * watch APK whose code collides with the phone's: they are separate artifacts of one application
 * id and each form factor needs its own number. Two literals in two files also drift, which is
 * how a watch ends up published as a version the phone never was.
 *
 * The codes live in separate bands rather than being derived from one another. A watch code of
 * "phone plus one" looks tidy and stops working the moment the phone is released twice in a row.
 */
object Versions {

    fun name(project: Project): String = property(project, "weighttrackVersionName")

    fun phoneCode(project: Project): Int = code(project)

    fun wearCode(project: Project): Int = band(project) + code(project)

    /** What a release check needs to know, without having to configure the Android plugin. */
    fun summary(project: Project): String =
        "name=${name(project)} phone=${phoneCode(project)} wear=${wearCode(project)}"

    private fun code(project: Project): Int {
        val value = property(project, "weighttrackVersionCode").toInt()
        require(value in 1 until band(project)) {
            "weighttrackVersionCode must be below the watch's band, or the two collide"
        }
        return value
    }

    private fun band(project: Project): Int =
        property(project, "weighttrackWearVersionBand").toInt()

    private fun property(project: Project, name: String): String =
        requireNotNull(project.findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }) {
            "$name is missing from gradle.properties"
        }
}
