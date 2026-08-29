package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.Codec
import io.github.humbleui.skija.ImageInfo
import org.blackaddons.blackskija.api.DeferredFree

/**
 * Where a [SkijaAnimation] gets its frames.
 *
 * Skia composites the frames of a GIF or an animated WebP itself, but decodes no APNG animation at
 * all, so that one is taken apart and composited here — see [ApngFrames]. Both look the same from
 * the outside: frames are numbered, each one is painted into a canvas-sized bitmap, and a frame may
 * be a delta on an earlier one rather than a whole picture.
 */
internal interface AnimationFrames : AutoCloseable {

    val count: Int

    /** The pixel layout of the canvas every frame is composited into. */
    val info: ImageInfo

    /** How many times the picture asks to repeat after its first pass; -1 means forever. */
    val repetitions: Int

    /** As authored, so a caller can tell "no delay given" (0) from a real one. */
    fun durationMs(index: Int): Int

    /** The frame [index] is a delta on, or -1 if it stands on its own. */
    fun requiredFrame(index: Int): Int

    /** Composites [index] into [into], which holds [prior] right now — or nothing, at -1. */
    fun readInto(into: Bitmap, index: Int, prior: Int)
}

/** The whole of the Skia-native path: it already knows how to replay one frame onto another. */
internal class CodecFrames(private val codec: Codec) : AnimationFrames {

    private val frames = codec.framesInfo

    override val count = frames.size.coerceAtLeast(1)
    override val info: ImageInfo get() = codec.imageInfo
    override val repetitions = codec.repetitionCount

    override fun durationMs(index: Int) = frames.getOrNull(index)?.duration ?: 0
    override fun requiredFrame(index: Int) = frames.getOrNull(index)?.requiredFrame ?: -1

    override fun readInto(into: Bitmap, index: Int, prior: Int) {
        codec.readPixels(into, index, prior)
    }

    override fun close() = DeferredFree.later(codec)
}
