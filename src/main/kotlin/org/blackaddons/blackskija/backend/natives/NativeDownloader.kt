package org.blackaddons.blackskija.backend.natives

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.zip.ZipFile

// Fetches a Skija platform native from Maven Central and extracts the needed files into the cache.
// The jar is a temp file, discarded after extraction.
internal object NativeDownloader {

    private val LOG = LoggerFactory.getLogger("blackskija")

    private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

    // Downloads skija-<classifier>-<version>.jar and extracts files into targetDir, each written
    // atomically. Throws on network/HTTP/missing-entry failure.
    fun fetch(classifier: String, version: String, files: List<String>, targetDir: Path) {
        val os = NativePlatform.osOf(classifier)
        val arch = NativePlatform.archOf(classifier)
        val url = "$MAVEN_CENTRAL/io/github/humbleui/skija-$classifier/$version/skija-$classifier-$version.jar"
        val resPrefix = "io/github/humbleui/skija/$os/$arch/"

        val tmpJar = Files.createTempFile("skija-$classifier-", ".jar")
        try {
            download(url, tmpJar)
            ZipFile(tmpJar.toFile()).use { zip ->
                for (file in files) {
                    val entry = zip.getEntry(resPrefix + file) ?: error("$file not found in $url")
                    val tmp = Files.createTempFile(targetDir, file, ".tmp")
                    zip.getInputStream(entry).use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
                    Files.move(tmp, targetDir.resolve(file), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            Files.deleteIfExists(tmpJar)
        }
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
}
