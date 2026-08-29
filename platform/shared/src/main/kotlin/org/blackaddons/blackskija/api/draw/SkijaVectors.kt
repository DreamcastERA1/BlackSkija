package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.Data
import io.github.humbleui.skija.Path
import io.github.humbleui.skija.svg.SVGDOM
import org.blackaddons.blackskija.api.DeferredFree
import org.blackaddons.blackskija.api.RenderThread
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.draw.SkijaVectors.icon
import org.blackaddons.blackskija.api.draw.SkijaVectors.svg
import org.blackaddons.blackskija.api.draw.SkijaVectors.svgResource
import java.awt.Color

/**
 * Vector art for the [Skija] draw layer: SVG path data and whole SVG documents, parsed once and kept.
 *
 * Two shapes of the same job, and the cheaper one covers icons. An icon set is a list of path strings
 * in a square viewBox, so [icon] draws one with *our* paint — which is what makes it tintable, and
 * lets one file serve every color and size an interface needs. A document ([svgResource], [svg])
 * carries its own fills, strokes and gradients and paints itself, so it renders art faithfully and
 * ignores any color you had in mind.
 *
 * Both are drawn at their authored resolution every frame — there is no raster to go soft — which is
 * the point of using them over a PNG at 13 pixels.
 */
object SkijaVectors {

    // Parsed vectors, kept for the life of the process. An interface draws a fixed handful of icons
    // over and over, so this is the same bargain as the blur masks: no eviction, and therefore no way
    // for a cached handle to be freed while a queued draw still points at it. A caller that really is
    // done with one says so through delete(), which parks the free in [DeferredFree].
    private val paths = HashMap<String, Path>()
    private val documents = HashMap<String, SkijaSvg>()

    /**
     * Draws an icon given as SVG path data (an `<svg><path d="…">`'s `d`) in a [size]-pixel square.
     *
     * [thickness] is the stroke width in viewBox units — 2 in a 24-unit box is what most line icon
     * sets are drawn at — and 0 fills the path instead, for a solid set.
     */
    fun icon(
        data: String, x: Number, y: Number, size: Number, color: Color,
        viewBox: Number = 24, thickness: Number = 2,
    ) = Skija.path(path(data), x, y, size, color, viewBox, thickness)

    /** Parses SVG path data into a cached [Path]. Draw it with [Skija.path]. */
    fun path(data: String): Path {
        // Parsing builds a native object right here, unlike an image, whose decode is deferred to the
        // first draw. So it is as render-thread-bound as the drawing is.
        RenderThread.require("a vector path was parsed")
        return paths.getOrPut(data) {
            Path.makeFromSVGString(data)
        }
    }

    /** Parses a classpath `.svg` into a cached document (e.g. `/assets/…/logo.svg`). */
    fun svgResource(path: String): SkijaSvg = documents[path] ?: run {
        val bytes = SkijaVectors::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("BlackSkija: SVG not found on classpath: $path")
        svg(path, bytes)
    }

    /**
     * Parses raw SVG bytes into a cached document under [key] — for art the classpath cannot reach.
     * Returns the document already held under [key] without re-reading [bytes].
     */
    fun svg(key: String, bytes: ByteArray): SkijaSvg {
        RenderThread.require("an SVG document was parsed")
        return documents.getOrPut(key) {
            // The document refs the data, so our handle to it has done its job once it is built.
            val data = Data.makeFromBytes(bytes)
            val dom = SVGDOM(data)
            data.close()
            SkijaSvg(dom)
        }
    }

    /** Forgets a cached path or document and frees it once the current frame has been drawn. */
    fun delete(key: String) {
        paths.remove(key)?.let { DeferredFree.later(it) }
        documents.remove(key)?.let { DeferredFree.later(it) }
    }
}
