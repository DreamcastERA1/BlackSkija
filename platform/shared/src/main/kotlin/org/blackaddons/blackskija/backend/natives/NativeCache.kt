package org.blackaddons.blackskija.backend.natives

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

// On-disk layout of the downloaded natives (<gameDir>/blackskija/skija/<version>/<classifier>/),
// version-keyed so different Skija versions coexist. Prunes stale versions.
internal object NativeCache {

    private val root: Path
        get() = FabricLoader.getInstance().gameDir.resolve("blackskija").resolve("skija")

    fun versionDir(version: String): Path = root.resolve(version)

    fun platformDir(version: String, classifier: String): Path = versionDir(version).resolve(classifier)

    // Only prune dirs that look like a version (e.g. "0.143.17"), so a stray file or unrelated folder
    // under the cache root is never deleted.
    private val VERSION_DIR = Regex("""\d[\d.]*""")

    fun prune(version: String) {
        runCatching {
            Files.list(root).use { stream ->
                stream.filter {
                    Files.isDirectory(it) &&
                        it.fileName.toString() != version &&
                        VERSION_DIR.matches(it.fileName.toString())
                }.forEach { it.toFile().deleteRecursively() }
            }
        }
    }
}
