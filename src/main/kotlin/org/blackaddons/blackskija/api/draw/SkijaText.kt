package org.blackaddons.blackskija.api.draw

import net.minecraft.ChatFormatting
import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaFonts
import org.blackaddons.blackskija.api.draw.SkijaText.drawGradient
import java.awt.Color

/**
 * Convenience text helpers over [Skija]: alignment around an anchor, optional drop shadow, gradient
 * text, greedy word-wrap, and stripping of Minecraft `§` codes. Core drawing lives in [Skija].
 */
object SkijaText {

    /** Process-global default text size for calls that omit `size`. Per-call `size` overrides it. */
    var defaultSize: Float = 9f

    /** Process-global default font family for calls that omit `family`. Per-call `family` overrides it. */
    var defaultFamily: String = SkijaFonts.DEFAULT

    fun width(text: String, size: Float = this.defaultSize, family: String = this.defaultFamily): Float =
        Skija.textWidth(stripFormatting(text), size, family)

    fun draw(
        text: String,
        x: Number,
        y: Number,
        color: Color = Color.WHITE,
        size: Float = this.defaultSize,
        family: String = this.defaultFamily,
        align: Skija.Align = Skija.Align.LEFT,
        shadow: Boolean = false,
    ) {
        val clean = stripFormatting(text)
        val drawX = alignedX(x.toFloat(), clean, size, family, align)
        if (shadow) Skija.textShadow(clean, drawX, y, size, color, family)
        else Skija.text(clean, drawX, y, size, color, family)
    }

    fun drawGradient(
        text: String,
        x: Number,
        y: Number,
        color1: Color,
        color2: Color,
        size: Float = this.defaultSize,
        family: String = this.defaultFamily,
        align: Skija.Align = Skija.Align.LEFT,
        gradient: Gradient = Gradient.LEFT_RIGHT,
    ) {
        val clean = stripFormatting(text)
        val drawX = alignedX(x.toFloat(), clean, size, family, align)
        Skija.textGradient(clean, drawX, y, size, color1, color2, gradient, family)
    }

    /** Multi-stop variant of [drawGradient]; see [Skija.textGradient] for [colors]/[stops]. */
    fun drawGradient(
        text: String,
        x: Number,
        y: Number,
        colors: List<Color>,
        size: Float = this.defaultSize,
        family: String = this.defaultFamily,
        align: Skija.Align = Skija.Align.LEFT,
        gradient: Gradient = Gradient.LEFT_RIGHT,
        stops: FloatArray? = null,
    ) {
        val clean = stripFormatting(text)
        val drawX = alignedX(x.toFloat(), clean, size, family, align)
        Skija.textGradient(clean, drawX, y, size, colors, gradient, family, stops)
    }

    /** Draws word-wrapped text; returns the laid-out block height. */
    fun drawWrapped(
        text: String,
        x: Number,
        y: Number,
        maxWidth: Number,
        color: Color = Color.WHITE,
        size: Float = this.defaultSize,
        family: String = this.defaultFamily,
        lineHeight: Number = 1f,
    ): Float = Skija.wrappedText(stripFormatting(text), x, y, maxWidth, size, color, family, lineHeight)

    /** Measures wrapped text without drawing; returns `[width, height]`. */
    fun wrappedBounds(
        text: String,
        maxWidth: Number,
        size: Float = this.defaultSize,
        family: String = this.defaultFamily,
        lineHeight: Number = 1f,
    ): FloatArray = Skija.wrappedTextBounds(stripFormatting(text), maxWidth, size, family, lineHeight)

    /** Greedy word-wrap into lines (for callers that need the lines, not just a draw). */
    fun wrap(text: String, maxWidth: Float, size: Float = this.defaultSize, family: String = this.defaultFamily): List<String> {
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

    // Skips the width measurement for the common LEFT case.
    private fun alignedX(x: Float, text: String, size: Float, family: String, align: Skija.Align): Float =
        if (align == Skija.Align.LEFT) x else alignX(x, width(text, size, family), align)

    private fun alignX(x: Float, w: Float, align: Skija.Align): Float = when (align) {
        Skija.Align.LEFT -> x
        Skija.Align.CENTER -> x - w / 2f
        Skija.Align.RIGHT -> x - w
    }

    /** Strips Minecraft `§` formatting codes, which the Skija text layer can't interpret. */
    fun stripFormatting(text: String): String = ChatFormatting.stripFormatting(text) ?: text
}
