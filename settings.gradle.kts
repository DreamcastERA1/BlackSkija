pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven-central.storage-download.googleapis.com/maven2/") { name = "Maven Central mirror" }
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("net.fabricmc.fabric-loom") version providers.gradleProperty("loom_version")
    }
}

rootProject.name = "blackskija"

// One build, one tree. The leaf is the Minecraft version a platform builds against; each shares the
// `platform/shared` source set, compiled anew against its own mappings.
include("platform:26.3")
include("platform:26.2")
include("platform:26.1.2")
