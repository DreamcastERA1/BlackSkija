plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// buildSrc is its own Gradle build, so `providers.gradleProperty` reads *its* properties, not the
// root's — hence the root file is read directly. The version still lives in exactly one place (the
// root gradle.properties), which settings.gradle.kts pins the Loom/Kotlin plugins to as well.
val kotlinVersion: String = file("../gradle.properties").readLines()
    .firstOrNull { it.startsWith("kotlin_version=") }
    ?.substringAfter('=')
    ?.trim()
    ?: error("kotlin_version missing from the root gradle.properties")

dependencies {
    // The Kotlin plugin must be on this classpath for a precompiled script plugin to apply kotlin("jvm").
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
}
