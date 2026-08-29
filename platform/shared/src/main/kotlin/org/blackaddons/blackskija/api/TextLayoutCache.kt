package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.paragraph.Paragraph
import io.github.humbleui.skija.paragraph.ParagraphBuilder
import io.github.humbleui.skija.paragraph.ParagraphStyle
import io.github.humbleui.skija.paragraph.TextStyle
import org.blackaddons.blackskija.api.TextLayoutCache.Entry
import org.blackaddons.blackskija.api.TextLayoutCache.evicted
import io.github.humbleui.skija.Font as SkFont

// Caches laid-out Skija Paragraphs (layout is Skija's costliest op), keyed by content+style+width,
// not color: color/alpha is applied per draw. Render-thread only (native objects shared with flush).
internal object TextLayoutCache {

    private const val MAX = 256

    private data class Key(
        val text: String, val size: Float, val family: String,
        val width: Float, val lineHeight: Float,
    )

    // Tracks the last solid color so an unchanged static draw can skip the recolor+relayout. solid is
    // false after a shader paint or before first coloring, forcing the next solid paint to reapply.
    private class Entry(val paragraph: Paragraph) {
        var lastArgb = 0
        var solid = false

        /**
         * Frame this paragraph was last recorded into a canvas by, or -1. Once the current frame has
         * recorded it, restyling it would retroactively change that draw, so the draw asking for a
         * different style gets a throwaway instead.
         */
        var paintedFrame = -1
    }

    // Evicted paragraphs, awaiting close(). They are NOT freed at eviction: painting a paragraph only
    // records it into the canvas, and the GPU work happens later, at the frame's flushAndSubmit. A
    // paragraph evicted after being painted but before that flush would have its native memory freed
    // while the pending display list still points at it — a use-after-free whose damage accumulates,
    // and whose visible form is glyphs rendered with the wrong or missing pieces. Text that changes
    // every frame (a coordinate readout) mints a new key per frame, so it evicts constantly and is
    // where this shows up first. Drained by releaseEvicted() once the frame has been flushed.
    private val evicted = ArrayList<Paragraph>()

    // Bumped once per composited frame, in releaseEvicted(). Entries remember the frame that recorded
    // them so a second draw of the same key can tell whether recoloring would corrupt a pending record.
    private var frame = 0

    private val cache = object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean {
            if (size <= MAX) return false
            evicted += eldest.value.paragraph
            return true
        }
    }

    /**
     * Frees the paragraphs evicted since the last call and opens the next frame. Must be called only
     * once the frame that could have painted them has been flushed and submitted — see [evicted].
     */
    fun releaseEvicted() {
        frame++
        if (evicted.isEmpty()) return
        for (paragraph in evicted) paragraph.close()
        evicted.clear()
    }

    private val paint = Paint()

    // Metrics depend only on the face and the size, so they cache far more coarsely than layouts.
    private val metricsCache = HashMap<Pair<String, Float>, FloatArray>()

    /**
     * `[ascent, descent]` in pixels, both positive. Read straight off the typeface rather than a
     * laid-out paragraph: it needs no text, and Skia's line box rounds to whole pixels.
     *
     * Falls back to `[size, 0]` for a family that was never registered — wrong, but bounded, and
     * only reachable if a caller asks about a font it never drew with.
     */
    fun metrics(size: Float, family: String): FloatArray {
        RenderThread.require("font metrics were read")
        return metricsCache.getOrPut(family to size) {
            val typeface = SkijaFonts.typeface(family) ?: return@getOrPut floatArrayOf(size, 0f)
            // Skia signs ascent negative (above the baseline) and descent positive.
            SkFont(typeface, size).use { floatArrayOf(-it.metrics.ascent, it.metrics.descent) }
        }
    }

    fun measureWidth(text: String, size: Float, family: String): Float =
        entry(text, size, family, Float.POSITIVE_INFINITY, 1f).paragraph.maxIntrinsicWidth

    fun measureHeight(text: String, size: Float, family: String, width: Float, lineHeight: Float): Float =
        entry(text, size, family, width, lineHeight).paragraph.height

    // [longestLine, height] for wrapped text at maxWidth.
    fun measureBounds(text: String, maxWidth: Float, size: Float, family: String, lineHeight: Float): FloatArray {
        val p = entry(text, size, family, maxWidth, lineHeight).paragraph
        return floatArrayOf(p.longestLine, p.height)
    }

    fun drawSolid(
        canvas: Canvas, text: String, size: Float, family: String,
        width: Float, lineHeight: Float, argb: Int, antiAlias: Boolean, x: Float, y: Float,
    ) {
        val e = entry(text, size, family, width, lineHeight)
        // Skija 0.143.17: updateForegroundPaint only takes effect during layout(), so recoloring needs
        // updateForegroundPaint then layout() before paint. Skipped when the color is unchanged.
        val recolor = !e.solid || e.lastArgb != argb
        if (recolor && e.paintedFrame == frame) {
            // This frame already recorded the cached paragraph in another color, and a record only
            // points at it — recoloring now would repaint the earlier draw too. Per-character chroma
            // hits this constantly: every glyph is its own key, and a repeated character wants a
            // different color each time.
            paint.reset()
            paint.isAntiAlias = antiAlias
            paint.color = argb
            oneOff(text, size, family, width, lineHeight, paint).paint(canvas, x, y)
            return
        }
        if (recolor) {
            paint.reset()
            paint.isAntiAlias = antiAlias
            paint.color = argb
            e.paragraph.updateForegroundPaint(0, text.length, paint)
            e.paragraph.layout(width)
            e.lastArgb = argb
            e.solid = true
        }
        e.paragraph.paint(canvas, x, y)
        e.paintedFrame = frame
    }

    // Non-solid foreground (e.g., a gradient shader). Always recolors+relayouts, and marks the paragraph
    // non-solid so a later solid paint reapplies its color instead of keeping the shader.
    fun drawShader(
        canvas: Canvas, text: String, size: Float, family: String,
        width: Float, lineHeight: Float, fg: Paint, x: Float, y: Float,
    ) {
        val e = entry(text, size, family, width, lineHeight)
        // A shader paint always re-styles, so once this frame has recorded the cached paragraph the
        // only safe option is a throwaway — see the same guard in drawSolid.
        if (e.paintedFrame == frame) {
            oneOff(text, size, family, width, lineHeight, fg).paint(canvas, x, y)
            return
        }
        e.paragraph.updateForegroundPaint(0, text.length, fg)
        e.paragraph.layout(width)
        e.solid = false
        e.paragraph.paint(canvas, x, y)
        e.paintedFrame = frame
    }

    /**
     * A paragraph for a single draw, styled with [fg] and freed once the frame has been flushed. Used
     * when the cached one is already spoken for by an earlier record in this frame; it rides the same
     * [evicted] list, so it obeys the same "free only after the flush" rule.
     */
    private fun oneOff(
        text: String, size: Float, family: String, width: Float, lineHeight: Float, fg: Paint,
    ): Paragraph {
        val p = build(text, size, family, lineHeight)
        p.updateForegroundPaint(0, text.length, fg)
        p.layout(width)
        evicted += p
        return p
    }

    // Cached laid-out entry for this content+style+width, built on a miss. Owned by the cache; never close it.
    private fun entry(text: String, size: Float, family: String, width: Float, lineHeight: Float): Entry {
        // Measuring is a layout, so it builds and caches a native paragraph exactly like drawing does —
        // which is why the measuring helpers are as thread-bound as the draws.
        RenderThread.require("text was measured or drawn")
        val key = Key(text, size, family, width, lineHeight)
        cache[key]?.let { return it }
        val p = build(text, size, family, lineHeight)
        p.layout(width)
        return Entry(p).also { cache[key] = it }
    }

    private fun build(text: String, size: Float, family: String, lineHeight: Float): Paragraph {
        val style = TextStyle().setFontSize(size).setFontFamilies(arrayOf(family))
        if (lineHeight != 1f) style.setHeight(lineHeight)
        val paraStyle = ParagraphStyle().setTextStyle(style)
        val builder = ParagraphBuilder(paraStyle, SkijaFonts.collection)
        builder.addText(text)
        val p = builder.build()
        builder.close()
        paraStyle.close()
        style.close()
        return p
    }
}
