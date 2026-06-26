package org.blackaddons.blackskija.api

import net.minecraft.ChatFormatting
import java.awt.Color

/**
 * Convenience text helpers over [Skija]: alignment around an anchor point, optional drop
 * shadow, gradient text, greedy word-wrap, and stripping of Minecraft `§` formatting
 * codes. Core drawing lives in [Skija]; this is the ergonomic layer UI code calls.
 */
object SkijaText {

    /** Defaults so call sites can omit size/font. */
    var size: Float = 9f
    var family: String = SkijaFonts.DEFAULT

    fun width(text: String, size: Float = this.size, family: String = this.family): Float =
        Skija.textWidth(stripFormatting(text), size, family)

    fun draw(
        text: String,
        x: Number,
        y: Number,
        color: Color = Color.WHITE,
        size: Float = this.size,
        family: String = this.family,
        align: Skija.Align = Skija.Align.LEFT,
        shadow: Boolean = false,
    ) {
        val clean = stripFormatting(text)
        val drawX = alignX(x.toFloat(), width(clean, size, family), align)
        if (shadow) Skija.textShadow(clean, drawX, y, size, color, family)
        else Skija.text(clean, drawX, y, size, color, family)
    }

    fun drawGradient(
        text: String,
        x: Number,
        y: Number,
        color1: Color,
        color2: Color,
        size: Float = this.size,
        family: String = this.family,
        align: Skija.Align = Skija.Align.LEFT,
        gradient: Gradient = Gradient.LEFT_RIGHT,
    ) {
        val clean = stripFormatting(text)
        val drawX = alignX(x.toFloat(), width(clean, size, family), align)
        Skija.textGradient(clean, drawX, y, size, color1, color2, gradient, family)
    }

    /** Draws word-wrapped text; returns the laid-out block height. */
    fun drawWrapped(
        text: String,
        x: Number,
        y: Number,
        maxWidth: Number,
        color: Color = Color.WHITE,
        size: Float = this.size,
        family: String = this.family,
        lineHeight: Number = 1f,
    ): Float = Skija.wrappedText(stripFormatting(text), x, y, maxWidth, size, color, family, lineHeight)

    /** Measures wrapped text without drawing; returns `[width, height]`. */
    fun wrappedBounds(
        text: String,
        maxWidth: Number,
        size: Float = this.size,
        family: String = this.family,
        lineHeight: Number = 1f,
    ): FloatArray = Skija.wrappedTextBounds(stripFormatting(text), maxWidth, size, family, lineHeight)

    /** Greedy word-wrap into lines (for callers that need the lines, not just a draw). */
    fun wrap(text: String, maxWidth: Float, size: Float = this.size, family: String = this.family): List<String> {
        val words = stripFormatting(text).split(' ')
        val lines = mutableListOf<String>()
        var line = StringBuilder()

        fun flush() {
            if (line.isNotEmpty()) {
                lines.add(line.toString())
                line = StringBuilder()
            }
        }

        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (width(candidate, size, family) <= maxWidth || line.isEmpty()) {
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            } else {
                flush()
                line.append(word)
            }
        }

        flush()
        return lines
    }

    private fun alignX(x: Float, w: Float, align: Skija.Align): Float = when (align) {
        Skija.Align.LEFT -> x
        Skija.Align.CENTER -> x - w / 2f
        Skija.Align.RIGHT -> x - w
    }

    /**
     * Strips Minecraft `§` formatting codes before measure/draw (the Skija text layer
     * can't interpret them). Delegates to vanilla [ChatFormatting.stripFormatting].
     */
    fun stripFormatting(text: String): String = ChatFormatting.stripFormatting(text) ?: text
}
