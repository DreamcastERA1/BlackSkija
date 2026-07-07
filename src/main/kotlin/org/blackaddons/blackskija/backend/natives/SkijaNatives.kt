package org.blackaddons.blackskija.backend.natives

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

// Provisions the Skija native at startup, off-thread, then points Skija at it via skija.library.path.
// Offline-safe: cache hit loads with no network, a miss downloads, a failure leaves the overlay disabled
// (never crashes) until a later online launch. version is pinned at build time so the native can't ABI-drift.
internal object SkijaNatives {

    private val LOG = LoggerFactory.getLogger("blackskija")

    @Volatile
    var ready = false
        private set

    private val started = AtomicBoolean(false)

    private val version: String by lazy {
        SkijaNatives::class.java.getResourceAsStream("/blackskija/skija.version")
            ?.use { it.readBytes().decodeToString().trim() }
            ?: error("blackskija: /blackskija/skija.version resource missing (build misconfigured)")
    }

    fun ensure() {
        if (started.getAndSet(true)) return
        Thread({
            runCatching { provision() }.onFailure { LOG.error("Skija native provisioning failed", it) }
        }, "blackskija-skija-natives").apply { isDaemon = true }.start()
    }

    private fun provision() {
        val classifier = NativePlatform.current ?: run {
            LOG.warn(
                "Unsupported OS/arch for Skija ({} / {}); BlackSkija overlay disabled.",
                System.getProperty("os.name"), System.getProperty("os.arch"),
            )
            return
        }
        if (!ensurePlatform(classifier)) {
            LOG.warn("Skija native for {} unavailable (offline with empty cache?); overlay disabled until an online launch.", classifier)
            return
        }
        System.setProperty("skija.library.path", NativeCache.platformDir(version, classifier).toAbsolutePath().toString())
        ready = true
        LOG.info("Skija native ready: {} ({})", classifier, version)

        NativeCache.prune(version)
    }

    // Ensures the platform's native files are present and verified, from cache or else downloaded.
    private fun ensurePlatform(classifier: String): Boolean {
        val platformDir = NativeCache.platformDir(version, classifier)
        val needed = NativePlatform.neededFiles(NativePlatform.osOf(classifier))

        val present = needed.all { val f = platformDir.resolve(it); Files.isRegularFile(f) && Files.size(f) > 0 }
        if (present && NativeIntegrity.verify(platformDir, classifier, needed, version)) return true

        return runCatching {
            Files.createDirectories(platformDir)
            NativeDownloader.fetch(classifier, version, needed, platformDir)
            if (NativeIntegrity.verify(platformDir, classifier, needed, version)) {
                true
            } else {
                LOG.error("Downloaded Skija native for {} failed SHA-256 verification, refusing to load.", classifier)
                false
            }
        }.getOrElse {
            LOG.warn("Could not provide Skija native for {}: {}", classifier, it.toString())
            false
        }
    }
}
