package org.blackaddons.blackskija.backend.natives

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// Verifies natives against SHA-256 pins baked at build time (/blackskija/skija.sha256). Catches a
// swapped/tampered native (a local-RCE primitive via System.load) and corruption. A missing or stale
// manifest degrades to a warning rather than bricking; files with no pin are skipped.
internal object NativeIntegrity {

    private val LOG = LoggerFactory.getLogger("blackskija")

    // Pinned <classifier>/<file> -> sha256, plus the version the manifest was generated for.
    private class Manifest(val hashes: Map<String, String>, val version: String?)

    private val manifest: Manifest by lazy { load() }
    private var staleWarned = false

    // True if every file matches its pin. Files with no pin, or no usable manifest, pass.
    fun verify(platformDir: Path, classifier: String, files: List<String>, version: String): Boolean {
        val hashes = usableHashes(version)
        for (file in files) {
            val expected = hashes["$classifier/$file"] ?: continue // unverified
            val actual = sha256(platformDir.resolve(file))
            if (actual != expected) {
                LOG.warn("SHA-256 mismatch for {}/{} (expected {}, got {})", classifier, file, expected, actual)
                return false
            }
        }
        return true
    }

    private fun usableHashes(version: String): Map<String, String> {
        val m = manifest
        if (m.version != null && m.version != version) {
            if (!staleWarned) {
                LOG.warn("Skija hash manifest is for '{}' but skija-shared is '{}'; integrity NOT verified (rebuild to refresh).", m.version, version)
                staleWarned = true
            }
            return emptyMap()
        }
        return m.hashes
    }

    private fun load(): Manifest {
        val text = NativeIntegrity::class.java.getResourceAsStream("/blackskija/skija.sha256")
            ?.use { it.readBytes().decodeToString() }
        if (text == null) {
            LOG.warn("Skija hash manifest missing; native integrity NOT verified.")
            return Manifest(emptyMap(), null)
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
        return Manifest(map, manifestVersion)
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
}
