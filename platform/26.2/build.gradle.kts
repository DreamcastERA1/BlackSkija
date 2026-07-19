// :platform:26.2 — the Minecraft 26.2 build: Vulkan + GL backends, and the assembly of the jar.
plugins {
    id("blackskija.platform")
    // Version pinned in settings.gradle.kts; applied here because a precompiled convention plugin
    // can't apply Loom (see blackskija.platform).
    id("net.fabricmc.fabric-loom")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

// Everything version-specific about 26.2, in one place. The 26.1.2 sibling declares its own.
val minecraftVersion = prop("mc262_minecraft_version")
val loaderVersion = prop("mc262_loader_version")
val fabricKotlinVersion = prop("mc262_fabric_kotlin_version")
val fabricApiVersion = prop("mc262_fabric_api_version")
val skijaVersion = prop("skija_version")
val typesVersion = prop("types_version")

loom {
    accessWidenerPath = rootProject.file("platform/shared/src/main/resources/blackskija.accesswidener")

    // Portable client run configs that force the GPU backend via a launch arg. Both editions run the
    // dev showcase (blackskija.demo), which BlackskijaClient gates to this project's own dev only.
    runs {
        create("clientVulkan") {
            client()
            displayName = "Minecraft Client (26.2 · Vulkan)"
            programArguments.addAll("--graphicsBackend", "vulkan")
            systemProperties.put("blackskija.demo", "true")
        }
        create("clientOpenGl") {
            client()
            displayName = "Minecraft Client (26.2 · OpenGL)"
            programArguments.addAll("--graphicsBackend", "opengl")
            systemProperties.put("blackskija.demo", "true")
        }
    }
}

dependencies {
    // 26.2 ships deobfuscated → plain `minecraft(...)`/`implementation(...)`, no mappings/modImpl.
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // JiJ: bundle skija-shared and its types dep so consumers don't install them; the native is
    // fetched at runtime by SkijaNatives against the fingerprint manifest.
    include("io.github.humbleui:skija-shared:$skijaVersion")
    include("io.github.humbleui:types:$typesVersion")
}

tasks.processResources {
    val props = mapOf(
        "version" to version,
        "minecraft_version" to minecraftVersion,
        "loader_version" to loaderVersion,
        "kotlin_loader_version" to fabricKotlinVersion,
        "skija_version" to skijaVersion,
    )
    props.forEach { (k, v) -> inputs.property(k, v) }
    filteringCharset = "UTF-8"
    filesMatching(listOf("fabric.mod.json", "blackskija/skija.version")) { expand(props) }
}
