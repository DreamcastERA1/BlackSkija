// :platform:26.3 — the Minecraft 26.3 build: Vulkan + GL backends, and the assembly of the jar.
plugins {
    id("blackskija.platform")
    // Version pinned in settings.gradle.kts; applied here because a precompiled convention plugin
    // can't apply Loom (see blackskija.platform).
    id("net.fabricmc.fabric-loom")
}

fun prop(name: String): String = providers.gradleProperty(name).get()

// Everything version-specific about 26.3, in one place. The 26.1.2 sibling declares its own.
val minecraftVersion = prop("mc263_minecraft_version")
val loaderVersion = prop("mc263_loader_version")
val fabricKotlinVersion = prop("mc263_fabric_kotlin_version")
val fabricApiVersion = prop("mc263_fabric_api_version")
val skijaVersion = prop("skija_version")
val typesVersion = prop("types_version")

loom {
    accessWidenerPath = file("src/main/resources/blackskija.accesswidener")

    // Portable client run configs that force the GPU backend via a launch arg. Both editions run the
    // dev showcase (blackskija.demo), which BlackskijaClient gates to this project's own dev only.
    runs {
        // The plain run keeps the in-game backend setting; the two below force one.
        named("client") {
            systemProperties.put("blackskija.demo", "true")
        }
        create("clientVulkan") {
            client()
            displayName = "Minecraft Client (26.3 · Vulkan)"
            programArguments.addAll("--graphicsBackend", "vulkan")
            systemProperties.put("blackskija.demo", "true")
        }
        create("clientOpenGl") {
            client()
            displayName = "Minecraft Client (26.3 · OpenGL)"
            programArguments.addAll("--graphicsBackend", "opengl")
            systemProperties.put("blackskija.demo", "true")
        }
    }
}

dependencies {
    // 26.3 ships deobfuscated → plain `minecraft(...)`/`implementation(...)`, no mappings/modImpl.
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
