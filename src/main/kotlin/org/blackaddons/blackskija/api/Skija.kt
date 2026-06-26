package org.blackaddons.blackskija.api

import io.github.humbleui.skija.BlendMode
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.ColorFilter
import io.github.humbleui.skija.FilterBlurMode
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.MaskFilter
import io.github.humbleui.skija.Matrix33
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.PaintMode
import io.github.humbleui.skija.PaintStrokeCap
import io.github.humbleui.skija.SamplingMode
import io.github.humbleui.skija.Shader
import io.github.humbleui.skija.paragraph.Paragraph
import io.github.humbleui.skija.paragraph.ParagraphBuilder
import io.github.humbleui.skija.paragraph.ParagraphStyle
import io.github.humbleui.skija.paragraph.TextStyle
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import org.joml.Matrix3x2fc
import java.awt.Color

/**
 * The framework's drawing surface — backend-agnostic, a thin API over a Skija `Canvas`.
 * Coordinates accept [Number] (no `.toFloat()` at call sites); colors are [Color].
 *
 * A frame is driven by `SkijaCompositor`: [beginFrame] binds the canvas, the draw
 * callback runs, then [endFrame]. Calls outside a frame are no-ops.
 */
object Skija {
    private var canvas: Canvas? = null
    private val paint = Paint()
    private val imgPaint = Paint()

    var antiAlias: Boolean = true

    private var alpha: Float = 1f
    private val alphaStack = ArrayDeque<Float>()

    fun beginFrame(canvas: Canvas, pose: Matrix3x2fc) {
        this.canvas = canvas
        alpha = 1f
        alphaStack.clear()
        canvas.save()
        canvas.concat(pose.toMatrix33())
    }

    fun endFrame() {
        canvas?.restore()
        canvas = null
    }

    private inline fun draw(block: (Canvas) -> Unit) {
        canvas?.let(block)
    }

    fun push() = draw {
        alphaStack.addLast(alpha)
        it.save()
    }

    fun pop() = draw {
        it.restore()
        alpha = if (alphaStack.isNotEmpty()) alphaStack.removeLast() else 1f
    }

    fun translate(x: Number, y: Number) = draw { it.translate(x.toFloat(), y.toFloat()) }
    fun scale(x: Number, y: Number) = draw { it.scale(x.toFloat(), y.toFloat()) }
    fun scale(n: Number) = scale(n, n)
    fun rotate(radians: Number) = draw { it.rotate(Math.toDegrees(radians.toDouble()).toFloat()) }
    fun transform(matrix: Matrix3x2fc) = draw { it.concat(matrix.toMatrix33()) }

    fun globalAlpha(amount: Number) { alpha = amount.toFloat().coerceIn(0f, 1f) }

    fun pushScissor(x: Number, y: Number, w: Number, h: Number) = draw {
        it.save()
        it.clipRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()))
    }

    fun popScissor() = draw { it.restore() }

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Color) = draw {
        it.drawRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()), fill(color))
    }

    fun rect(x: Number, y: Number, w: Number, h: Number, color: Color, radius: Number) = draw {
        it.drawRRect(RRect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat()), fill(color))
    }

    fun hollowRect(x: Number, y: Number, w: Number, h: Number, thickness: Number, color: Color, radius: Number) = draw {
        it.drawRRect(
            RRect.makeXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radius.toFloat()),
            stroke(color, thickness.toFloat()),
        )
    }

    /** Filled rect rounded on only the top corners ([roundTop]) or only the bottom. */
    fun drawHalfRoundedRect(
        x: Number, y: Number, w: Number, h: Number, color: Color, radius: Number, roundTop: Boolean,
    ) = draw {
        val r = radius.toFloat()
        val radii = if (roundTop) floatArrayOf(r, r, 0f, 0f) else floatArrayOf(0f, 0f, r, r) // tl, tr, br, bl
        it.drawRRect(RRect.makeComplexXYWH(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), radii), fill(color))
    }

    /**
     * Soft drop shadow: a rounded box grown by [spread] and Gaussian-blurred by [blur],
     * in [color]. Draw it BEFORE the element it sits under.
     */
    fun dropShadow(
        x: Number, y: Number, w: Number, h: Number,
        blur: Number, spread: Number, radius: Number, color: Color = Color(0, 0, 0, 128),
    ) = draw {
        val s = spread.toFloat()
        val rect = RRect.makeXYWH(x.toFloat() - s, y.toFloat() - s, w.toFloat() + 2 * s, h.toFloat() + 2 * s, radius.toFloat())
        val sigma = blur.toFloat() / 2f
        val mask = if (sigma > 0f) MaskFilter.makeBlur(FilterBlurMode.NORMAL, sigma) else null
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.color = argb(color)
        paint.maskFilter = mask
        it.drawRRect(rect, paint)
        paint.maskFilter = null
        mask?.close()
    }

    fun line(x1: Number, y1: Number, x2: Number, y2: Number, thickness: Number, color: Color) = draw {
        it.drawLine(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
            stroke(color, thickness.toFloat()).setStrokeCap(PaintStrokeCap.ROUND),
        )
    }

    fun circle(x: Number, y: Number, radius: Number, color: Color) = draw {
        it.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), fill(color))
    }

    fun gradientRect(
        x: Number, y: Number, w: Number, h: Number,
        color1: Color, color2: Color, gradient: Gradient, radius: Number,
    ) = draw {
        val fx = x.toFloat(); val fy = y.toFloat(); val fw = w.toFloat(); val fh = h.toFloat()
        val (x1, y1) = when (gradient) {
            Gradient.LEFT_RIGHT -> fx + fw to fy
            Gradient.TOP_BOTTOM -> fx to fy + fh
        }
        val shader = Shader.makeLinearGradient(
            fx, fy, x1, y1,
            intArrayOf(argb(color1), argb(color2)),
        )
        paint.reset()
        paint.isAntiAlias = antiAlias
        paint.shader = shader
        it.drawRRect(RRect.makeXYWH(fx, fy, fw, fh, radius.toFloat()), paint)
        paint.shader = null
        shader.close()
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

    /** Horizontal alignment, applied by [SkijaText] around an anchor point. */
    enum class Align { LEFT, CENTER, RIGHT }

    fun text(text: String, x: Number, y: Number, size: Number, color: Color, family: String = SkijaFonts.DEFAULT) = draw {
        val p = paragraph(text, size.toFloat(), family, argb(color), null)
        p.layout(Float.POSITIVE_INFINITY)
        p.paint(it, x.toFloat(), y.toFloat())
        p.close()
    }

    /** [text] with a 1px-offset dark drop shadow underneath. */
    fun textShadow(text: String, x: Number, y: Number, size: Number, color: Color, family: String = SkijaFonts.DEFAULT) = draw {
        val fx = x.toFloat(); val fy = y.toFloat(); val fs = size.toFloat()
        val shadow = paragraph(text, fs, family, argb(Color(0, 0, 0, color.alpha)), null)
        shadow.layout(Float.POSITIVE_INFINITY)
        shadow.paint(it, fx + 1f, fy + 1f)
        shadow.close()
        val main = paragraph(text, fs, family, argb(color), null)
        main.layout(Float.POSITIVE_INFINITY)
        main.paint(it, fx, fy)
        main.close()
    }

    /** Width of [text] at [size]; safe to call outside a frame (measuring only). */
    fun textWidth(text: String, size: Number, family: String = SkijaFonts.DEFAULT): Float {
        val p = paragraph(text, size.toFloat(), family, -1, null)
        p.layout(Float.POSITIVE_INFINITY)
        val w = p.maxIntrinsicWidth
        p.close()
        return w
    }

    fun textGradient(
        text: String, x: Number, y: Number, size: Number,
        color1: Color, color2: Color, gradient: Gradient, family: String = SkijaFonts.DEFAULT,
    ) = draw {
        val fx = x.toFloat(); val fy = y.toFloat(); val fs = size.toFloat()
        val w = textWidth(text, fs, family)
        val (x1, y1) = when (gradient) {
            Gradient.LEFT_RIGHT -> fx + w to fy
            Gradient.TOP_BOTTOM -> fx to fy + fs
        }
        val shader = Shader.makeLinearGradient(fx, fy, x1, y1, intArrayOf(argb(color1), argb(color2)))
        val fg = Paint().setShader(shader)
        val p = paragraph(text, fs, family, -1, fg)
        p.layout(Float.POSITIVE_INFINITY)
        p.paint(it, fx, fy)
        p.close()
        fg.close()
        shader.close()
    }

    /**
     * Word-wrapped text in `[x, x+maxWidth]`; returns the laid-out block height.
     * [lineHeight] is a multiple of the font size (1 = natural leading).
     */
    fun wrappedText(
        text: String, x: Number, y: Number, maxWidth: Number, size: Number, color: Color,
        family: String = SkijaFonts.DEFAULT, lineHeight: Number = 1f,
    ): Float {
        val c = canvas ?: return 0f
        val p = paragraph(text, size.toFloat(), family, argb(color), null, lineHeight.toFloat())
        p.layout(maxWidth.toFloat())
        p.paint(c, x.toFloat(), y.toFloat())
        val h = p.height
        p.close()
        return h
    }

    /**
     * Measures wrapped text without drawing (safe outside a frame). Returns
     * `[width, height]` — width is the longest laid-out line, ≤ [maxWidth].
     */
    fun wrappedTextBounds(
        text: String, maxWidth: Number, size: Number,
        family: String = SkijaFonts.DEFAULT, lineHeight: Number = 1f,
    ): FloatArray {
        val p = paragraph(text, size.toFloat(), family, -1, null, lineHeight.toFloat())
        p.layout(maxWidth.toFloat())
        val bounds = floatArrayOf(p.longestLine, p.height)
        p.close()
        return bounds
    }

    /**
     * Builds a single-style [Paragraph] (caller lays out and closes). A negative
     * [colorArgb] means "ignore" — used when [foreground] supplies the paint instead.
     * [lineHeight] != 1 overrides line spacing to that multiple of the font size.
     */
    private fun paragraph(
        text: String, size: Float, family: String, colorArgb: Int, foreground: Paint?, lineHeight: Float = 1f,
    ): Paragraph {
        val style = TextStyle().setFontSize(size).setFontFamilies(arrayOf(family))
        if (foreground != null) style.foreground = foreground else style.color = colorArgb
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

    /** Folds the current [alpha] multiplier into the color's alpha. */
    private fun argb(color: Color): Int {
        val a = (color.alpha * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
    }
}

/**
 * JOML 2D affine ([Matrix3x2fc], column-major) → Skija `Matrix33` (row-major 3x3).
 */
private fun Matrix3x2fc.toMatrix33(): Matrix33 =
    Matrix33(
        m00(), m10(), m20(),
        m01(), m11(), m21(),
        0f, 0f, 1f,
    )
