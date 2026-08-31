plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(org.gradle.api.artifacts.dsl.LockMode.STRICT)
}

buildscript.configurations.matching { it.name == "classpath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
