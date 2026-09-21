package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.FontEdging
import io.github.humbleui.skija.FontHinting
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.TextLine
import io.github.humbleui.skija.Typeface
import java.util.BitSet
import io.github.humbleui.skija.Font as SkFont

// Single-line text, shaped once and drawn with hinting off.
//
// Paragraph builds its own SkFont and Skija's TextStyle exposes no hinting control, so every string
// went through Skia's default grid fitting: the bottoms of round glyphs flatten onto the baseline
// into a visible shelf. A font we own can say hinting = NONE. Measured on a raster surface against
// the real native before this was written: the old paragraph output is pixel-identical to a TextLine
// at hinting NORMAL + subpixel, so the hinting knob is the whole of the difference.
//
// Two things make it safe to swap paths under the same coordinates. Drawing at `y + ascent` lands on
// exactly the pixels Paragraph painted from its own top-left (Paragraph's alphabetic baseline IS the
// ascent, at every size we draw at), and a shaped width matches the paragraph's to the last decimal
// — but only while the family's own face covers the string. A fallback run shapes through the
// shaper's font manager here and through a FontCollection there, and the two disagree by pixels, so
// anything the face does not cover stays on [TextLayoutCache].
//
// A TextLine also takes its color from the Paint at draw time, so none of the restyle hazards that
// [TextLayoutCache] has to guard against can exist on this path.
internal object TextLineCache {

    private const val MAX = 256

    private const val NO_GLYPH: Short = 0

    private data class Key(val text: String, val size: Float, val family: String)

    private data class FontKey(val family: String, val size: Float)

    private class Entry(val line: TextLine, val ascent: Float)

    // Evicted lines are parked, not closed: a line already recorded this frame is read by the GPU at
    // the flush. Same rule, and the same drain, as the paragraphs next door.
    private val lines = object : LinkedHashMap<Key, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>): Boolean {
            if (size <= MAX) return false
            DeferredFree.later(eldest.value.line)
            return true
        }
    }

    private val fonts = HashMap<FontKey, SkFont>()

    private val coverage = HashMap<String, Coverage>()

    private var fontGeneration = SkijaFonts.generation

    private val paint = Paint()

    /** Whether [family]'s own face covers [text], the condition for this path to be equivalent. */
    fun covers(text: String, family: String): Boolean {
        if (text.isEmpty()) return false
        invalidateIfFontsChanged()
        val typeface = SkijaFonts.typeface(family) ?: return false
        return coverage.getOrPut(family) { Coverage(typeface) }.covers(text)
    }

    fun drawSolid(
        canvas: Canvas, text: String, size: Float, family: String,
        argb: Int, antiAlias: Boolean, x: Float, y: Float,
    ) {
        val entry = entry(text, size, family) ?: return
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.color = argb
        canvas.drawTextLine(entry.line, x, y + entry.ascent, paint)
    }

    // Non-solid foreground (a gradient shader). No restyle and no throwaway: the paint is the draw's,
    // and the record copies it, so the caller may close its shader the moment this returns.
    fun drawShader(canvas: Canvas, text: String, size: Float, family: String, fg: Paint, x: Float, y: Float) {
        val entry = entry(text, size, family) ?: return
        canvas.drawTextLine(entry.line, x, y + entry.ascent, fg)
    }

    fun width(text: String, size: Float, family: String): Float = entry(text, size, family)?.line?.width ?: 0f

    private fun entry(text: String, size: Float, family: String): Entry? {
        RenderThread.require("text was measured or drawn")
        invalidateIfFontsChanged()
        val key = Key(text, size, family)
        lines[key]?.let { return it }
        val font = font(size, family) ?: return null
        return Entry(TextLine.make(text, font), -font.metrics.ascent).also { lines[key] = it }
    }

    private fun font(size: Float, family: String): SkFont? {
        val key = FontKey(family, size)
        fonts[key]?.let { return it }
        val typeface = SkijaFonts.typeface(family) ?: return null
        val font = SkFont(typeface, size)
            .setHinting(FontHinting.NONE)
            .setSubpixel(true)
            .setEdging(FontEdging.ANTI_ALIAS)
        fonts[key] = font
        return font
    }

    // A family re-registered with a different face leaves every shaped line and every coverage answer
    // describing the old one, so the whole lot is dropped rather than answered wrongly.
    private fun invalidateIfFontsChanged() {
        if (fontGeneration == SkijaFonts.generation) return
        fontGeneration = SkijaFonts.generation
        for (entry in lines.values) DeferredFree.later(entry.line)
        lines.clear()
        for (font in fonts.values) DeferredFree.later(font)
        fonts.clear()
        coverage.clear()
    }

    // Per-face glyph coverage, remembered by codepoint: the answer never changes for a given face,
    // and text that changes every frame (a coordinate readout) asks about the same handful of them.
    private class Coverage(private val typeface: Typeface) {

        private val known = BitSet()
        private val covered = BitSet()

        fun covers(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                // A line break is a layout instruction, not a glyph — leave those to the paragraph.
                if (cp == '\n'.code || cp == '\r'.code) return false
                if (!known.get(cp)) {
                    known.set(cp)
                    if (typeface.getUTF32Glyph(cp) != NO_GLYPH) covered.set(cp)
                }
                if (!covered.get(cp)) return false
            }
            return true
        }
    }
}
