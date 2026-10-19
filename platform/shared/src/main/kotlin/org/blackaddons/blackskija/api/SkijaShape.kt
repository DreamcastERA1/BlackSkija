package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Path
import io.github.humbleui.skija.PathBuilder
import io.github.humbleui.types.Rect

/**
 * Geometry built once and drawn by reference: any mix of lines, curves, rectangles and polygons,
 * as one native path. Build one with [Skija.shape], draw it with [Skija.fillShape] /
 * [Skija.strokeShape], and hand it back with [Skija.deleteShape] once its geometry is stale.
 *
 * What it is for is geometry that is drawn every frame but changes rarely - a thousand wires, a map
 * outline, a grid. Built per frame it costs a native call per segment and Skia re-tessellates it
 * every time; kept, it costs one draw, and Skia caches its tessellation against the path itself.
 *
 * The native handle is private so it cannot be freed while a queued draw still points at it.
 */
class SkijaShape internal constructor(internal val native: Path) {

    internal var deleted = false

    /** Whether [Skija.deleteShape] has been called on it. Drawing a deleted shape throws. */
    val isDeleted: Boolean get() = deleted

    /** The box around every point of the shape, control points included; stroke width is not. */
    val bounds: Rect get() = native.bounds
}

/**
 * What a [Skija.shape] block records into. Coordinates are `Float` on purpose: a shape is fed in
 * loops of thousands, where boxing every `Number` would cost more than the geometry.
 */
class ShapeBuilder internal constructor(private val builder: PathBuilder) {

    // Reused for every rect: addPolygon copies it across JNI, so one array serves them all.
    private val quad = FloatArray(8)

    /** Starts a new contour at ([x], [y]). */
    fun moveTo(x: Float, y: Float) {
        builder.moveTo(x, y)
    }

    fun lineTo(x: Float, y: Float) {
        builder.lineTo(x, y)
    }

    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) {
        builder.quadTo(cx, cy, x, y)
    }

    /** A cubic Bezier from the current point through control points 1 and 2 to ([x], [y]). */
    fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        builder.cubicTo(c1x, c1y, c2x, c2y, x, y)
    }

    /** Closes the current contour back to where it started. */
    fun close() {
        builder.closePath()
    }

    /** A straight segment as a contour of its own. */
    fun segment(x1: Float, y1: Float, x2: Float, y2: Float) {
        builder.moveTo(x1, y1)
        builder.lineTo(x2, y2)
    }

    /** A closed axis-aligned rectangle: a box when filled, an outline when stroked. */
    fun rect(x: Float, y: Float, w: Float, h: Float) {
        quad[0] = x; quad[1] = y
        quad[2] = x + w; quad[3] = y
        quad[4] = x + w; quad[5] = y + h
        quad[6] = x; quad[7] = y + h
        builder.addPolygon(quad, true)
    }

    /**
     * A closed polygon through [points] - interleaved `x, y`, three vertices or more - with a tangent
     * arc of [corner] radius at every vertex. The same outline [Skija.polygon] draws.
     */
    fun polygon(points: FloatArray, corner: Float = 0f) {
        Skija.requirePolygon(points)
        Skija.appendPolygon(builder, points, corner)
    }
}

