package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.Picture
import io.github.humbleui.types.Rect

/**
 * A run of drawing recorded once and replayed until it changes. Draw through it with [Skija.layer]:
 * while the layer is current its content is not run at all, and the whole run costs one queued op and
 * one picture playback - no per-call JVM work, no per-call JNI. [invalidate] it when what it shows
 * changes, and the next [Skija.layer] call records it again.
 *
 * Recorded as a Skia picture, so it is resolution-independent: drawn under a different transform
 * (a pan, a zoom) it is still sharp, and still needs no re-recording. That is what makes it the
 * right cache for a large canvas - record what is around the view, then move the view freely.
 *
 * A layer captures what its content draws **at the moment it is recorded**:
 * - The global alpha in force when it is *drawn* still applies to it; alpha changed inside the content
 *   is baked in.
 * - Animated content (Lottie, animated images, anything time-based) freezes at the recorded frame.
 * - Items and entities cannot be drawn into a layer and throw if they are: they borrow Minecraft's
 *   textures for a single frame, and a picture replayed later would read freed memory. The same holds
 *   for an image wrapped around a Minecraft texture (`SkijaTextures.wrap`, `SkijaImages.drawMc`);
 *   images BlackSkija decoded itself are fine.
 *
 * Render-thread only. [clear] it when done; it owns a native picture.
 */
class SkijaLayer {

    internal var picture: Picture? = null

    // What the content queued on the frame it was recorded, turned into [picture] when replayed.
    internal var pending: List<(Canvas) -> Unit>? = null

    // The box the content was recorded in: the picture's cull rect.
    internal var bounds: Rect = Rect.makeWH(0f, 0f)

    internal var stale = true

    /** How many times its content has run - for a profiler overlay, or a test that it stayed cached. */
    var recordings: Int = 0
        internal set

    /** Whether the next [Skija.layer] call will run its content. */
    val isStale: Boolean get() = stale

    /** Marks what it shows as changed: the next [Skija.layer] call records it again. */
    fun invalidate() {
        stale = true
    }

    /**
     * Frees the recording, after the frame that may still draw it. The layer stays usable: drawn
     * again, it records afresh.
     */
    fun clear() {
        RenderThread.require("a layer was cleared")
        stale = true
        pending = null
        picture?.let(DeferredFree::later)
        picture = null
    }
}
