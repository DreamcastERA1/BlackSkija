package org.blackaddons.blackskija.backend

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

object SkijaNatives {

    private val LOG = LoggerFactory.getLogger("blackskija")

    private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

    /** True once the current platform's native is provisioned and `skija.library.path` is set. */
    @Volatile
    var ready = false
        private set

    private val started = AtomicBoolean(false)

    /** Version of Skija to fetch — baked at build time to match the bundled `skija-shared`. */
    private val version: String by lazy {
        SkijaNatives::class.java.getResourceAsStream("/blackskija/skija.version")
            ?.use { it.readBytes().decodeToString().trim() }
            ?: error("blackskija: /blackskija/skija.version resource missing (build misconfigured)")
    }

    /**
     * Pinned SHA-256 of each native file, keyed `<classifier>/<file>`, baked at build time
     * by `generateSkijaHashes`. Empty (→ integrity unverified, with a warning) if the
     * manifest is missing or stale, so a forgotten rebuild degrades instead of bricking.
     */
    private val hashes: Map<String, String> by lazy {
        val text = SkijaNatives::class.java.getResourceAsStream("/blackskija/skija.sha256")
            ?.use { it.readBytes().decodeToString() }
        if (text == null) {
            LOG.warn("Skija hash manifest missing — native integrity NOT verified.")
            return@lazy emptyMap()
        }
        val map = HashMap<String, String>()
        var manifestVersion: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=').takeIf { it > 0 } ?: continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key == "version") manifestVersion = value else map[key] = value.lowercase()
        }
        if (manifestVersion != version) {
            LOG.warn("Skija hash manifest is for '{}' but skija-shared is '{}' — integrity NOT verified (rebuild to refresh).", manifestVersion, version)
            return@lazy emptyMap()
        }
        map
    }

    fun ensure() {
        if (started.getAndSet(true)) return
        Thread({
            runCatching { provision() }.onFailure { LOG.error("Skija native provisioning failed", it) }
        }, "blackskija-skija-natives").apply { isDaemon = true }.start()
    }

    private fun provision() {
        val current = currentClassifier() ?: run {
            LOG.warn(
                "Unsupported OS/arch for Skija ({} / {}); BlackSkija overlay disabled.",
                System.getProperty("os.name"), System.getProperty("os.arch"),
            )
            return
        }
        val versionDir = cacheRoot().resolve(version)
        if (!ensurePlatform(current, versionDir)) {
            LOG.warn("Skija native for {} unavailable (offline with empty cache?); overlay disabled until an online launch.", current)
            return
        }
        System.setProperty("skija.library.path", versionDir.resolve(current).toAbsolutePath().toString())
        ready = true
        LOG.info("Skija native ready: {} ({})", current, version)

        pruneOldVersions(versionDir)
    }

    /** Ensures [classifier]'s native files are in the cache (from cache or download). */
    private fun ensurePlatform(classifier: String, versionDir: Path): Boolean {
        val os = classifier.substringBefore('-')
        val arch = classifier.substringAfter('-')
        val platformDir = versionDir.resolve(classifier)
        val needed = buildList {
            add(libFileName(os))
            if (os == "windows") add("icudtl.dat")
        }

        val present = needed.all { val f = platformDir.resolve(it); Files.isRegularFile(f) && Files.size(f) > 0 }
        if (present && verify(platformDir, classifier, needed)) return true

        return runCatching {
            Files.createDirectories(platformDir)
            val url = "$MAVEN_CENTRAL/io/github/humbleui/skija-$classifier/$version/skija-$classifier-$version.jar"
            val tmpJar = Files.createTempFile("skija-$classifier-", ".jar")
            try {
                download(url, tmpJar)
                val resPrefix = "io/github/humbleui/skija/$os/$arch/"
                ZipFile(tmpJar.toFile()).use { zip ->
                    for (file in needed) {
                        val entry = zip.getEntry(resPrefix + file) ?: error("$file not found in $url")
                        val target = platformDir.resolve(file)
                        val tmp = Files.createTempFile(platformDir, file, ".tmp")
                        zip.getInputStream(entry).use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } finally {
                Files.deleteIfExists(tmpJar)
            }
            if (verify(platformDir, classifier, needed)) {
                true
            } else {
                LOG.error("Downloaded Skija native for {} failed SHA-256 verification — refusing to load.", classifier)
                false
            }
        }.getOrElse {
            LOG.warn("Could not provide Skija native for {}: {}", classifier, it.toString())
            false
        }
    }

    /** True if every needed file matches its pinned SHA-256 (files with no pin are skipped). */
    private fun verify(platformDir: Path, classifier: String, needed: List<String>): Boolean {
        for (file in needed) {
            val expected = hashes["$classifier/$file"] ?: continue // unverified (warned at load)
            val actual = sha256(platformDir.resolve(file))
            if (actual != expected) {
                LOG.warn("SHA-256 mismatch for {}/{} (expected {}, got {})", classifier, file, expected, actual)
                return false
            }
        }
        return true
    }

    private fun sha256(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, target: Path) {
        LOG.info("Downloading Skija native: {}", url)
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build()
        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofFile(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
        )
        if (response.statusCode() != 200) error("HTTP ${response.statusCode()} for $url")
    }

    private fun pruneOldVersions(keep: Path) {
        runCatching {
            val root = keep.parent ?: return
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) && it.fileName.toString() != version }
                    .forEach { it.toFile().deleteRecursively() }
            }
        }
    }

    private fun cacheRoot(): Path = FabricLoader.getInstance().gameDir.resolve("blackskija").resolve("skija")

    private fun libFileName(os: String): String = when (os) {
        "windows" -> "skija.dll"
        "linux" -> "libskija.so"
        "macos" -> "libskija.dylib"
        else -> error("unknown os: $os")
    }

    private fun currentClassifier(): String? {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val os = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("nux") || osName.contains("nix") -> "linux"
            else -> return null
        }
        val arch = when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> "arm64"
            osArch.contains("amd64") || osArch.contains("x86_64") || osArch == "x64" -> "x64"
            else -> return null
        }
        return "$os-$arch"
    }
}
