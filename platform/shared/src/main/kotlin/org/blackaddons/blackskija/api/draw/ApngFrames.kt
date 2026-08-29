package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.BlendMode
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.ImageInfo
import io.github.humbleui.skija.Paint
import io.github.humbleui.types.IRect
import io.github.humbleui.types.Rect
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * An APNG, taken apart and composited here because Skia will not do it.
 *
 * Skia's codec reads an APNG as a still: it reports one frame and hands back the default image, so
 * an animated sticker sat on its first frame forever. That is upstream Skia rather than the
 * bindings — the newer Skia behind Skiko answers the same — and Discord serves its free sticker
 * packs in exactly this format, so it is worth doing by hand rather than reaching for a dependency.
 *
 * The way in is that an APNG already is a container of PNGs. A frame's `fdAT` payload is an `IDAT`
 * stream with a sequence number glued to the front, so a frame can be rebuilt as a standalone PNG —
 * the original header narrowed to the frame, the palette and transparency chunks copied over, the
 * payload re-tagged — and handed to Skia, which decodes an ordinary PNG perfectly well. What is
 * left is the compositing the format asks for: every frame lands at its own offset, blended over
 * what came before or written straight onto it, and the frame it lands on is disposed of after.
 */
internal class ApngFrames private constructor(
    private val frames: List<Frame>,
    private val width: Int,
    private val height: Int,
    plays: Int,
) : AnimationFrames {

    override val count = frames.size
    override val info: ImageInfo = ImageInfo.makeN32Premul(width, height)

    // `num_plays` counts passes including the first, where the shared contract counts the repeats
    // after it; 0 is the format's way of saying forever.
    override val repetitions = if (plays <= 0) -1 else plays - 1

    private val source = Paint().setBlendMode(BlendMode.SRC)

    // The canvas belongs to the bitmap it was made for, and SkijaAnimation allocates that lazily.
    private var canvas: Canvas? = null
    private var canvasOf: Bitmap? = null

    // The canvas as it stood before the frame now showing was painted, kept only while that frame
    // asks to be undone rather than left alone or cleared.
    private var undo: Image? = null

    override fun durationMs(index: Int) = frames[index].delayMs

    // A frame that covers the whole canvas and overwrites what it covers needs nothing before it,
    // which is what most encoders emit and what makes seeking cheap.
    override fun requiredFrame(index: Int) =
        if (index > 0 && !frames[index].standsAlone(width, height)) index - 1 else -1

    override fun readInto(into: Bitmap, index: Int, prior: Int) {
        val canvas = canvasOn(into)
        if (prior < 0) into.erase(TRANSPARENT) else dispose(canvas, into, frames[prior])
        val frame = frames[index]
        if (frame.dispose == DISPOSE_PREVIOUS) {
            undo?.close()
            // A copy, because the bitmap underneath is about to be painted on.
            undo = Image.makeRasterFromBitmap(into)
        }
        val image = Image.makeDeferredFromEncodedBytes(frame.png)
        try {
            val x = frame.x.toFloat()
            val y = frame.y.toFloat()
            if (frame.blend == BLEND_OVER) canvas.drawImage(image, x, y)
            else canvas.drawImage(image, x, y, source)
        } finally {
            // Painting into a raster bitmap happens here and now, so nothing outlives this call.
            image.close()
        }
    }

    // Clears away the frame that was showing, the way that frame asked to be cleared away.
    private fun dispose(canvas: Canvas, into: Bitmap, frame: Frame) {
        when (frame.dispose) {
            DISPOSE_BACKGROUND -> into.erase(TRANSPARENT, frame.bounds)
            DISPOSE_PREVIOUS -> undo?.let { snapshot ->
                canvas.save()
                canvas.clipRect(frame.rect)
                canvas.drawImage(snapshot, 0f, 0f, source)
                canvas.restore()
            }
        }
    }

    private fun canvasOn(into: Bitmap): Canvas =
        canvas?.takeIf { canvasOf === into } ?: Canvas(into).also {
            canvas?.close()
            canvas = it
            canvasOf = into
        }

    override fun close() {
        canvas?.close()
        canvas = null
        canvasOf = null
        undo?.close()
        undo = null
        source.close()
    }

    private class Frame(
        val png: ByteArray,
        val x: Int,
        val y: Int,
        val delayMs: Int,
        val dispose: Int,
        val blend: Int,
        val width: Int,
        val height: Int,
    ) {
        val rect: Rect = Rect.makeXYWH(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
        val bounds: IRect = IRect.makeXYWH(x, y, width, height)

        fun standsAlone(canvasWidth: Int, canvasHeight: Int) =
            blend == BLEND_SOURCE && x == 0 && y == 0 && width == canvasWidth && height == canvasHeight
    }

    // A chunk of the source file, held as a range so nothing is copied until it is wanted.
    private class Chunk(private val png: ByteArray, val type: String, val from: Int, val to: Int) {
        fun data(skip: Int = 0): ByteArray = png.copyOfRange(from + 8 + skip, to - 4)
        fun raw(): ByteArray = png.copyOfRange(from, to)
    }

    companion object {

        /**
         * Reads [bytes] as an APNG, or returns null when it is anything else — a still PNG, a GIF,
         * a WebP — which Skia's own codec handles and [CodecFrames] wraps.
         */
        fun of(bytes: ByteArray): ApngFrames? {
            val chunks = chunks(bytes) ?: return null
            val header = chunks.firstOrNull { it.type == IHDR }?.data()?.takeIf { it.size >= 13 }
                ?: return null
            val control = chunks.firstOrNull { it.type == ACTL }?.data()?.takeIf { it.size >= 8 }
                ?: return null
            val width = int(header, 0)
            val height = int(header, 4)
            if (width <= 0 || height <= 0) return null

            // Palette, transparency, colour intent: whatever a frame needs to decode the way the
            // whole picture does. Only what precedes the image data can apply to it.
            val untilImage = chunks.indexOfFirst { it.type == IDAT }.takeIf { it >= 0 } ?: chunks.size
            val shared = chunks.take(untilImage).filter { it.type !in ANIMATION_CHUNKS }.map { it.raw() }

            val frames = ArrayList<Frame>()
            val parts = ArrayList<ByteArray>()
            var fctl: ByteArray? = null

            fun flush() {
                val f = fctl ?: return
                fctl = null
                val w = int(f, 4)
                val h = int(f, 8)
                if (parts.isNotEmpty() && w > 0 && h > 0) {
                    frames += Frame(
                        png = rebuild(header, shared, parts, w, h),
                        x = int(f, 12),
                        y = int(f, 16),
                        delayMs = delayMs(short(f, 20), short(f, 22)),
                        dispose = f[24].toInt(),
                        blend = f[25].toInt(),
                        width = w,
                        height = h,
                    )
                }
                parts.clear()
            }

            for (chunk in chunks) when (chunk.type) {
                FCTL -> {
                    flush()
                    fctl = chunk.data().takeIf { it.size >= 26 }
                }
                // The default image joins the animation only when a control chunk claims it first;
                // otherwise it is the still that non-APNG readers show, and no frame of ours.
                IDAT -> if (fctl != null) parts += chunk.data()
                FDAT -> if (fctl != null && chunk.to - chunk.from > 16) parts += chunk.data(skip = 4)
            }
            flush()

            return if (frames.isEmpty()) null else ApngFrames(frames, width, height, int(control, 4))
        }

        private fun chunks(png: ByteArray): List<Chunk>? {
            if (png.size < SIGNATURE.size || SIGNATURE.indices.any { png[it] != SIGNATURE[it] }) return null
            val out = ArrayList<Chunk>()
            var at = SIGNATURE.size
            while (at + 12 <= png.size) {
                val length = int(png, at)
                val end = at + 12 + length
                if (length < 0 || end > png.size) return null
                val type = String(png, at + 4, 4, Charsets.US_ASCII)
                out += Chunk(png, type, at, end)
                if (type == IEND) break
                at = end
            }
            return out
        }

        // The frame as a PNG in its own right: the file's header narrowed to the frame's size, the
        // shared chunks verbatim, and the frame's payload wearing an IDAT label.
        private fun rebuild(
            header: ByteArray,
            shared: List<ByteArray>,
            parts: List<ByteArray>,
            width: Int,
            height: Int,
        ): ByteArray {
            val ihdr = header.copyOf()
            writeInt(ihdr, 0, width)
            writeInt(ihdr, 4, height)
            val out = ByteArrayOutputStream(parts.sumOf { it.size } + 1024)
            out.writeBytes(SIGNATURE)
            chunk(out, IHDR, ihdr)
            shared.forEach(out::writeBytes)
            parts.forEach { chunk(out, IDAT, it) }
            chunk(out, IEND, ByteArray(0))
            return out.toByteArray()
        }

        private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val name = type.toByteArray(Charsets.US_ASCII)
            out.writeBytes(bigEndian(data.size))
            out.writeBytes(name)
            out.writeBytes(data)
            val crc = CRC32().apply { update(name); update(data) }
            out.writeBytes(bigEndian(crc.value.toInt()))
        }

        // A denominator of 0 means hundredths of a second, which is the GIF unit and the usual case.
        private fun delayMs(numerator: Int, denominator: Int) =
            if (denominator == 0) numerator * 10 else numerator * 1000 / denominator

        private fun int(data: ByteArray, at: Int) =
            (data[at].toInt() and 0xFF shl 24) or (data[at + 1].toInt() and 0xFF shl 16) or
                (data[at + 2].toInt() and 0xFF shl 8) or (data[at + 3].toInt() and 0xFF)

        private fun short(data: ByteArray, at: Int) =
            (data[at].toInt() and 0xFF shl 8) or (data[at + 1].toInt() and 0xFF)

        private fun writeInt(data: ByteArray, at: Int, value: Int) {
            for (i in 0 until 4) data[at + i] = (value ushr (24 - 8 * i)).toByte()
        }

        private fun bigEndian(value: Int) = ByteArray(4).also { writeInt(it, 0, value) }

        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        private const val IHDR = "IHDR"
        private const val IDAT = "IDAT"
        private const val IEND = "IEND"
        private const val ACTL = "acTL"
        private const val FCTL = "fcTL"
        private const val FDAT = "fdAT"

        // Chunks that describe the animation rather than a picture, so no rebuilt frame carries one.
        private val ANIMATION_CHUNKS = setOf(IHDR, ACTL, FCTL, FDAT, IDAT, IEND)

        private const val TRANSPARENT = 0

        private const val DISPOSE_BACKGROUND = 1
        private const val DISPOSE_PREVIOUS = 2

        private const val BLEND_SOURCE = 0
        private const val BLEND_OVER = 1
    }
}
