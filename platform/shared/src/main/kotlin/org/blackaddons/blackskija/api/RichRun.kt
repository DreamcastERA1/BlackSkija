package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Image

/**
 * One run of [Skija.richText]: text, or a picture that sits in the line like a glyph and wraps with it -
 * an emoji, most of the time.
 */
sealed interface RichRun {
    data class Text(val text: String) : RichRun

    /**
     * [image], or the `srcX, srcY, srcW, srcH` part of it (a cell of an atlas), drawn [scale] ems square
     * and centred on the line. The layout depends only on [scale], so swapping [image] - the next frame of
     * an animation - costs nothing but the draw.
     */
    data class Picture(
        val image: Image,
        val srcX: Float = 0f,
        val srcY: Float = 0f,
        val srcW: Float = image.width.toFloat(),
        val srcH: Float = image.height.toFloat(),
        val scale: Float = 1.2f,
    ) : RichRun
}
