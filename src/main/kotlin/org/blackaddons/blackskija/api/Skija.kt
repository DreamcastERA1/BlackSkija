package org.blackaddons.blackskija.api

import io.github.humbleui.skija.*
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import org.blackaddons.blackskija.api.Skija.flush
import org.blackaddons.blackskija.api.Skija.gradientRect
import org.blackaddons.blackskija.api.Skija.rect
import org.blackaddons.blackskija.api.Skija.text
import org.blackaddons.blackskija.api.Skija.textGradient
import org.blackaddons.blackskija.api.Skija.textWidth
import org.blackaddons.blackskija.api.Skija.wrappedTextBounds
import org.joml.Matrix3x2fc
import org.slf4j.LoggerFactory
import java.awt.Color
import kotlin.math.roundToInt

/**
 * Backend-agnostic drawing surface, a thin API over a Skija Canvas. Coordinates take [Number]
 * (no `.toFloat()` at call sites); colors are [java.awt.Color].
 *
 * Immediate-mode: call [rect], [text] and friends from anywhere on the render thread with no
 * begin/end. Each call is appended to a per-frame buffer that `SkijaCompositor` replays via
 * [flush] inside one isolated GPU frame, then clears. An empty buffer costs nothing.
 *
 * Render-thread only, including the measuring helpers ([textWidth], [wrappedTextBounds]): they
 * build and reuse native paragraphs in a shared cache, so calling them off-thread can race a
 * frame flush and corrupt the native allocator.
 */
object Skija {
    private val LOG = LoggerFactory.getLogger("blackskija")

    private val paint = Paint()
    private val imgPaint = Paint()
    private val textPaint = Paint()

    // This frame's queued draws, replayed in order at flush and reused across frames. State ops
    // (alpha/push/pop) share the list with draws so per-call alpha and transforms sequence right.
    private val batch = ArrayList<(Canvas) -> Unit>()

    /** Global antialiasing toggle, read at composite time. Persistent, not per-call or per-frame. */
    var antiAlias: Boolean = true

    private var alpha: Float = 1f
    private val alphaStack = ArrayDeque<Float>()

    // Canvas saves we pushed this frame; guards pop/popScissor against imbalanced caller push/pop.
    private var saveDepth = 0

    // Blur masks reused across frames, keyed by sigma. Never closed; they live for the process.
    private val blurMaskCache = HashMap<Float, MaskFilter>()

    internal fun hasContent(): Boolean = batch.isNotEmpty()

    internal fun discard() {
        batch.clear()
    }

    internal fun flush(canvas: Canvas, pose: Matrix3x2fc) {
        alpha = 1f
        alphaStack.clear()
        saveDepth = 0
        val base = canvas.save()
        canvas.concat(pose.toMatrix33())
        try {
            // Indexed loop: tolerant of op queuing further work mid-replay.
            var i = 0
            while (i < batch.size) {
                batch[i](canvas)
                i++
            }
        } finally {
            if (saveDepth != 0 || alphaStack.isNotEmpty()) {
                LOG.warn(
                    "BlackSkija: unbalanced push/pushScissor in frame (saveDepth={}, alphaStack={}) - auto-unwound",
                    saveDepth, alphaStack.size,
                )
            }
            // Unwind to base regardless of imbalance or a mid-batch throw, so leftover clips or
            // transforms can't bleed into the next frame (the canvas is reused).
            canvas.restoreToCount(base)
            batch.clear()
            alpha = 1f
            alphaStack.clear()
            saveDepth = 0
        }
    }

    private fun draw(op: (Canvas) -> Unit) {
        batch.add(op)
    }

    // For SkijaItems, which draws straight to the canvas at replay time rather than queuing more Skija calls.
    internal fun enqueue(op: (Canvas) -> Unit) {
        batch.add(op)
    }

    fun push() = draw {
        alphaStack.addLast(alpha)
        it.save()
        saveDepth++
    }

    fun pop() = draw {
        if (saveDepth > 0) {
            it.restore()
            saveDepth--
        }
        alpha = if (alphaStack.isNotEmpty()) alphaStack.removeLast() else 1f
    }

    fun translate(x: Float, y: Float) = draw { it.translate(x, y) }
    fun translate(x: Number, y: Number) = translate(x.toFloat(), y.toFloat())
    fun scale(x: Float, y: Float) = draw { it.scale(x, y) }
    fun scale(x: Number, y: Number) = scale(x.toFloat(), y.toFloat())
    fun scale(n: Number) = scale(n.toFloat(), n.toFloat())
    fun rotate(radians: Number) = draw { it.rotate(Math.toDegrees(radians.toDouble()).toFloat()) }
    fun transform(matrix: Matrix3x2fc) = draw { it.concat(matrix.toMatrix33()) }

    fun globalAlpha(amount: Number) = draw { alpha = amount.toFloat().coerceIn(0f, 1f) }

    fun pushScissor(x: Number, y: Number, w: Number, h: Number) = draw {
        it.save()
        saveDepth++
        it.clipRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()))
    }

    fun popScissor() = draw {
        if (saveDepth > 0) {
            it.restore()
            saveDepth--
        }
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color) = draw {
        it.drawRect(Rect.makeXYWH(x, y, w, h), fill(color))
    }

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Color) =
        rect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), color)

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Color, radius: Float) = draw {
        it.drawRRect(RRect.makeXYWH(x, y, w, h, radius), fill(color))
    }

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Color, radius: Number) =
        rect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), color, radius.toFloat())

    fun hollowRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color: Color, radius: Float) = draw {
        it.drawRRect(RRect.makeXYWH(x, y, w, h, radius), stroke(color, thickness))
    }

    fun hollowRect(x: Number, y: Number, w: Number, h: Number, thickness: Number, color: Color, radius: Number) =
        hollowRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), thickness.toFloat(), color, radius.toFloat())

    /** Filled rect rounded on only the top corners ([roundTop]) or only the bottom. */
    fun drawHalfRoundedRect(
        x: Number, y: Number, w: Number, h: Number, color: Color, radius: Number, roundTop: Boolean,
    ) = draw {
        val r = radius.toFloat()
        val radii = if (roundTop) floatArrayOf(r, r, 0f, 0f) else floatArrayOf(0f, 0f, r, r) // tl, tr, br, bl
        it.drawRRect(RRect.makeComplexXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radii), fill(color))
    }

    /** Soft drop shadow in [color]: a rounded box grown by [spread] and blurred by [blur]. Draw it before the element on top. */
    fun dropShadow(
        x: Number, y: Number, w: Number, h: Number,
        blur: Number, spread: Number, radius: Number, color: Color = Color(0, 0, 0, 128),
    ) = draw {
        val s = spread.toFloat()
        val rect = RRect.makeXYWH(x.toFloat() - s, y.toFloat() - s, w.toFloat() + 2 * s, h.toFloat() + 2 * s, radius.toFloat())
        val sigma = blur.toFloat() / 2f
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.color = argb(color)
        paint.maskFilter = blurMask(sigma)
        it.drawRRect(rect, paint)
        paint.maskFilter = null
    }

    private fun blurMask(sigma: Float): MaskFilter? {
        if (sigma <= 0f) return null
        return blurMaskCache.getOrPut(sigma) { MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma) }
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Color) = draw {
        it.drawLine(x1, y1, x2, y2, stroke(color, thickness).setStrokeCap(PaintStrokeCap.ROUND))
    }

    fun line(x1: Number, y1: Number, x2: Number, y2: Number, thickness: Number, color: Color) =
        line(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), thickness.toFloat(), color)

    fun circle(x: Float, y: Float, radius: Float, color: Color) = draw {
        it.drawCircle(x, y, radius, fill(color))
    }

    fun circle(x: Number, y: Number, radius: Number, color: Color) =
        circle(x.toFloat(), y.toFloat(), radius.toFloat(), color)

    /**
     * Filled rounded rect painted with a linear gradient across [colors] (2+). [stops], if given,
     * must match [colors] in length, each in `0..1` ascending; null spaces them evenly. [gradient]
     * picks the axis.
     */
    fun gradientRect(
        x: Number, y: Number, w: Number, h: Number,
        colors: List<Color>, gradient: Gradient, radius: Number, stops: FloatArray? = null,
    ) = draw {
        val fx = x.toFloat(); val fy = y.toFloat(); val fw = w.toFloat(); val fh = h.toFloat()
        val shader = linearShader(fx, fy, fw, fh, gradient, colors, stops)
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.shader = shader
        it.drawRRect(RRect.makeXYWH(fx, fy, fw, fh, radius.toFloat()), paint)
        paint.shader = null
        shader.close()
    }

    /** Two-color convenience for [gradientRect]. */
    fun gradientRect(
        x: Number, y: Number, w: Number, h: Number,
        color1: Color, color2: Color, gradient: Gradient, radius: Number,
    ) = gradientRect(x, y, w, h, listOf(color1, color2), gradient, radius)

    private fun linearShader(
        fx: Float, fy: Float, fw: Float, fh: Float,
        gradient: Gradient, colors: List<Color>, stops: FloatArray?,
    ): Shader {
        val x1: Float; val y1: Float
        when (gradient) {
            Gradient.LEFT_RIGHT -> { x1 = fx + fw; y1 = fy }
            Gradient.TOP_BOTTOM -> { x1 = fx; y1 = fy + fh }
        }
        val argbColors = IntArray(colors.size) { argb(colors[it]) }
        return Shader.makeLinearGradient(fx, fy, x1, y1, argbColors, stops)
    }

    fun image(
        image: Image, x: Number, y: Number, w: Number, h: Number,
        radius: Number = 0, tint: Color? = null,
    ) = draw {
        drawImage(it, image, null, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat(), tint)
    }

    fun image(
        image: Image, srcX: Number, srcY: Number, srcW: Number, srcH: Number,
        x: Number, y: Number, w: Number, h: Number, radius: Number = 0, tint: Color? = null,
    ) = draw {
        val src = Rect.makeXYWH(srcX.toFloat(), srcY.toFloat(), srcW.toFloat(), srcH.toFloat())
        drawImage(it, image, src, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat(), tint)
    }

    private fun drawImage(
        canvas: Canvas, image: Image, src: Rect?, x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Color?,
    ) {
        val srcRect = src ?: Rect.makeWH(image.width.toFloat(), image.height.toFloat())
        val dstRect = Rect.makeXYWH(x, y, w, h)
        val tintFilter = tint?.let { ColorFilter.makeBlend(argb(it), BlendMode.MODULATE) }
        imgPaint.reset()
        imgPaint.isAntiAlias = antiAlias
        imgPaint.colorFilter = tintFilter
        if (tintFilter == null) imgPaint.color = argb(Color.WHITE) // alpha = global alpha
        val rounded = radius > 0f
        if (rounded) {
            canvas.save()
            canvas.clipRRect(RRect.makeXYWH(x, y, w, h, radius), antiAlias)
        }
        canvas.drawImageRect(image, srcRect, dstRect, SamplingMode.DEFAULT, imgPaint, true)
        if (rounded) canvas.restore()
        imgPaint.colorFilter = null
        tintFilter?.close()
    }

    // Direct (non-queued) subregion draw, for SkijaItems to call from inside its own queued op.
    internal fun drawImageDirect(
        canvas: Canvas, image: Image,
        srcX: Float, srcY: Float, srcW: Float, srcH: Float,
        x: Float, y: Float, w: Float, h: Float, radius: Float, tint: Color?,
    ) {
        drawImage(canvas, image, Rect.makeXYWH(srcX, srcY, srcW, srcH), x, y, w, h, radius, tint)
    }

    enum class Align { LEFT, CENTER, RIGHT }

    fun text(text: String, x: Number, y: Number, size: Number, color: Color, family: String = SkijaFonts.DEFAULT) = draw {
        TextLayoutCache.drawSolid(it, text, size.toFloat(), family, Float.POSITIVE_INFINITY, 1f, argb(color), antiAlias, x.toFloat(), y.toFloat())
    }

    /** [text] with a 1px-offset dark drop shadow underneath. */
    fun textShadow(text: String, x: Number, y: Number, size: Number, color: Color, family: String = SkijaFonts.DEFAULT) = draw {
        val fx = x.toFloat(); val fy = y.toFloat(); val fs = size.toFloat()
        TextLayoutCache.drawSolid(it, text, fs, family, Float.POSITIVE_INFINITY, 1f, argb(Color(0, 0, 0, color.alpha)), antiAlias, fx + 1f, fy + 1f)
        TextLayoutCache.drawSolid(it, text, fs, family, Float.POSITIVE_INFINITY, 1f, argb(color), antiAlias, fx, fy)
    }

    /** Width of [text] at [size]. Render-thread only (builds/reuses a native paragraph). */
    fun textWidth(text: String, size: Number, family: String = SkijaFonts.DEFAULT): Float =
        TextLayoutCache.measureWidth(text, size.toFloat(), family)

    /**
     * Vertical extent of one line of [family] at [size], as `[ascent, descent]` in pixels —
     * both positive, measured from the baseline.
     *
     * [size] is an **em** size, so it is not the height of the drawn glyphs: `ascent + descent`
     * is, and it typically runs to ~1.3x [size]. Text drawn by [text] occupies `y` through
     * `y + ascent + descent`, so centering a line in a box needs these numbers, not [size].
     */
    fun textMetrics(size: Number, family: String = SkijaFonts.DEFAULT): FloatArray =
        TextLayoutCache.metrics(size.toFloat(), family)

    /**
     * Draws [text] filled with a linear gradient across [colors] (2+); see [gradientRect] for
     * [stops]/[gradient]. The gradient spans the measured text width (horizontal) or the font
     * [size] (vertical).
     */
    fun textGradient(
        text: String, x: Number, y: Number, size: Number,
        colors: List<Color>, gradient: Gradient, family: String = SkijaFonts.DEFAULT, stops: FloatArray? = null,
    ) = draw {
        val fx = x.toFloat(); val fy = y.toFloat(); val fs = size.toFloat()
        val shader = linearShader(fx, fy, TextLayoutCache.measureWidth(text, fs, family), fs, gradient, colors, stops)
        textPaint.reset()
        textPaint.isAntiAlias = antiAlias
        textPaint.shader = shader
        // updateForegroundPaint copies the paint and refs the shader into the cached paragraph,
        // so closing our handle right after painting is safe.
        TextLayoutCache.drawShader(it, text, fs, family, Float.POSITIVE_INFINITY, 1f, textPaint, fx, fy)
        textPaint.shader = null
        shader.close()
    }

    /** Two-color convenience for [textGradient]. */
    fun textGradient(
        text: String, x: Number, y: Number, size: Number,
        color1: Color, color2: Color, gradient: Gradient, family: String = SkijaFonts.DEFAULT,
    ) = textGradient(text, x, y, size, listOf(color1, color2), gradient, family)

    /**
     * Word-wrapped text in `[x, x+maxWidth]`; returns the laid-out block height.
     * [lineHeight] is a multiple of the font size (1 = natural leading).
     */
    fun wrappedText(
        text: String, x: Number, y: Number, maxWidth: Number, size: Number, color: Color,
        family: String = SkijaFonts.DEFAULT, lineHeight: Number = 1f,
    ): Float {
        val fs = size.toFloat()
        val mw = maxWidth.toFloat()
        val lh = lineHeight.toFloat()
        // Layout is color-independent: measure now, reuse the same cached paragraph at draw time.
        val height = TextLayoutCache.measureHeight(text, fs, family, mw, lh)
        draw {
            TextLayoutCache.drawSolid(it, text, fs, family, mw, lh, argb(color), antiAlias, x.toFloat(), y.toFloat())
        }
        return height
    }

    /**
     * Measures wrapped text without drawing. Render-thread only. Returns `[width, height]`, where
     * width is the longest laid-out line (<= [maxWidth]).
     */
    fun wrappedTextBounds(
        text: String, maxWidth: Number, size: Number,
        family: String = SkijaFonts.DEFAULT, lineHeight: Number = 1f,
    ): FloatArray = TextLayoutCache.measureBounds(text, maxWidth.toFloat(), size.toFloat(), family, lineHeight.toFloat())

    private fun fill(color: Color): Paint {
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.mode = PaintMode.FILL
        paint.color = argb(color)
        return paint
    }

    private fun stroke(color: Color, width: Float): Paint {
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.mode = PaintMode.STROKE
        paint.strokeWidth = width
        paint.color = argb(color)
        return paint
    }

    // Folds the current alpha multiplier into the color's alpha.
    private fun argb(color: Color): Int {
        val a = (color.alpha * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
    }
}

// JOML 2D affine (column-major) to Skija Matrix33 (row-major 3x3).
private fun Matrix3x2fc.toMatrix33(): Matrix33 =
    Matrix33(
        m00(), m10(), m20(),
        m01(), m11(), m21(),
        0f, 0f, 1f,
    )
