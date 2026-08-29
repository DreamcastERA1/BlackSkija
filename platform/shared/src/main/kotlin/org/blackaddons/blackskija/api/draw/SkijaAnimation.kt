package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.Codec
import io.github.humbleui.skija.Image
import org.blackaddons.blackskija.api.DeferredFree
import org.blackaddons.blackskija.api.RenderThread
import org.blackaddons.blackskija.api.Skija

/**
 * An animated picture — a GIF, an animated WebP — as a frame you can draw at any point in time.
 *
 * Skia decodes one frame at a time, and a frame is usually a *delta* on the one before it, so the
 * frames are decoded on demand and only ever forward from what is already in hand: playing at speed
 * costs one decode per frame shown, and nothing is decoded for an animation nobody is looking at.
 * Decoding all of them up front would be simpler and would hold width × height × 4 bytes per frame
 * for as long as the picture existed.
 *
 * Ask for [imageAt] every frame and draw what it gives you; it returns the same [Image] until the
 * animation actually moves on. Loops forever — [durationMs] and [repetitions] are there for a caller
 * that would rather stop.
 *
 * Render-thread only, like everything that touches a native handle here. [SkijaImages.animated] is
 * how you get one, and it owns it — say [SkijaImages.delete] rather than closing it yourself.
 */
class SkijaAnimation internal constructor(private val codec: Codec) : AutoCloseable {

    private val frames = codec.framesInfo

    /** Frames in the picture. 1 for a still, which is a legitimate one-frame animation. */
    val frameCount: Int = frames.size.coerceAtLeast(1)

    val width: Int = codec.imageInfo.width
    val height: Int = codec.imageInfo.height

    // Cumulative end time of each frame, so a lookup is a scan of one small array.
    private val ends = IntArray(frameCount)

    /** One full pass, in milliseconds. */
    val durationMs: Int

    /** How many times the picture asks to repeat after its first pass; -1 means forever. */
    val repetitions: Int = codec.repetitionCount

    init {
        var total = 0
        for (i in 0 until frameCount) {
            total += frameDuration(i)
            ends[i] = total
        }
        durationMs = total
    }

    // Allocated on the first decode, not with the object: callers build one of these just to read
    // [frameCount] and drop it again when the answer is 1, and a full-size bitmap is the one
    // expensive thing here. A still the size of a screenshot would allocate and free megabytes to
    // answer a question about its header.
    private var bitmap: Bitmap? = null

    // Frame currently held in `bitmap`, and the one `current` was made from. Separate because a
    // decode can fail halfway and leave the bitmap holding something other than what was asked for.
    private var decoded = -1
    private var shownIndex = -1
    private var current: Image? = null
    private var closed = false

    /** Which frame is showing at [elapsedMs] since the animation started. */
    fun frameIndexAt(elapsedMs: Long): Int {
        if (frameCount <= 1 || durationMs <= 0) return 0
        val t = (elapsedMs % durationMs).toInt()
        for (i in 0 until frameCount) if (t < ends[i]) return i
        return frameCount - 1
    }

    /**
     * The frame showing at [elapsedMs] since the animation started, decoded if it isn't already.
     *
     * The returned image belongs to this animation and is replaced when the animation moves on, so
     * draw it, don't keep it. Returns null only if the very first frame cannot be decoded.
     */
    fun imageAt(elapsedMs: Long): Image? {
        RenderThread.require("an animation frame was requested")
        if (closed) return null
        val target = frameIndexAt(elapsedMs)
        if (target == shownIndex) return current
        if (!decode(target)) return current
        // The bitmap is mutable and about to be drawn on again, so this makes a copy rather than
        // sharing its pixels — which is what lets the previous frame stay valid until its last draw.
        val image = Image.makeRasterFromBitmap(pixels())
        current?.let { DeferredFree.later(it) }
        current = image
        shownIndex = target
        return image
    }

    private fun pixels(): Bitmap =
        bitmap ?: Bitmap().apply { allocPixels(codec.imageInfo) }.also { bitmap = it }

    // Decodes forward to `target`, replaying only the frames it depends on and that we don't hold.
    private fun decode(target: Int): Boolean {
        val chain = ArrayList<Int>()
        var at = target
        while (at != decoded) {
            chain += at
            val required = frames.getOrNull(at)?.requiredFrame ?: -1
            if (required < 0) break
            at = required
        }
        // Whatever the walk stopped on: the frame already in the bitmap, or nothing at all.
        var prior = if (at == decoded) decoded else -1
        for (i in chain.indices.reversed()) {
            val frame = chain[i]
            val ok = runCatching { codec.readPixels(pixels(), frame, prior) }.isSuccess
            if (!ok) {
                // The bitmap now holds something we can't name; force the next attempt to start over.
                decoded = -1
                return false
            }
            prior = frame
            decoded = frame
        }
        return true
    }

    // A GIF authored with no delay means "as fast as sensible", which every renderer reads as 100ms.
    // Taken literally it plays at the frame rate of the game and looks like a strobe.
    private fun frameDuration(index: Int): Int {
        val duration = frames.getOrNull(index)?.duration ?: DEFAULT_FRAME_MS
        return if (duration <= MIN_FRAME_MS) DEFAULT_FRAME_MS else duration
    }

    /** Draws the frame showing at [elapsedMs]; a no-op while the first frame is still undecodable. */
    fun draw(elapsedMs: Long, x: Number, y: Number, w: Number, h: Number, radius: Number = 0) {
        val image = imageAt(elapsedMs) ?: return
        Skija.image(image, x, y, w, h, radius)
    }

    override fun close() {
        if (closed) return
        closed = true
        current?.let { DeferredFree.later(it) }
        current = null
        bitmap?.let { DeferredFree.later(it) }
        bitmap = null
        DeferredFree.later(codec)
    }

    private companion object {
        const val MIN_FRAME_MS = 10
        const val DEFAULT_FRAME_MS = 100
    }
}
