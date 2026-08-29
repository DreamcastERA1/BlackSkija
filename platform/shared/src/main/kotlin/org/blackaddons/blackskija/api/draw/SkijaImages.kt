package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.Codec
import io.github.humbleui.skija.Data
import io.github.humbleui.skija.Image
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.Identifier
import org.blackaddons.blackskija.api.DeferredFree
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaTextures
import org.blackaddons.blackskija.api.draw.SkijaImages.animated
import org.blackaddons.blackskija.api.draw.SkijaImages.drawMc
import org.blackaddons.blackskija.api.draw.SkijaImages.drawMcSprite
import org.blackaddons.blackskija.api.draw.SkijaImages.fromEncoded
import org.blackaddons.blackskija.api.draw.SkijaImages.resource
import java.awt.Color

/**
 * Image sources for the [Skija] draw layer:
 *  - [resource]: decode a classpath PNG/JPG into a cached Skija [Image].
 *  - [animated]: a GIF or animated WebP as a [SkijaAnimation], a frame at a time.
 *  - [drawMc] / [drawMcSprite]: draw a live Minecraft texture (resource-pack aware) by borrowing
 *    its GPU handle, no CPU copy.
 */
object SkijaImages {

    private const val RESOURCE_CACHE_MAX = 128
    private const val ANIMATION_CACHE_MAX = 16

    // Evicting parks the handle rather than closing it: a draw queued earlier this frame still
    // points at it and only replays at the flush. See [DeferredFree].
    private val resourceCache = object : LinkedHashMap<String, Image>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>): Boolean {
            if (size > RESOURCE_CACHE_MAX) { DeferredFree.later(eldest.value); return true }
            return false
        }
    }

    private val animationCache = object : LinkedHashMap<String, SkijaAnimation>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkijaAnimation>): Boolean {
            if (size > ANIMATION_CACHE_MAX) { DeferredFree.later(eldest.value); return true }
            return false
        }
    }

    /** Decodes a classpath PNG/JPG into a cached Skija [Image] (e.g. `/assets/.../x.png`). */
    fun resource(path: String): Image = resourceCache.getOrPut(path) {
        val bytes = SkijaImages::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("BlackSkija: image not found on classpath: $path")
        Image.makeDeferredFromEncodedBytes(bytes)
    }

    /**
     * Wraps raw encoded image bytes (PNG/JPG) as a cached deferred [Image], keyed by
     * [key]. Lets callers feed images the classpath can't reach — an http-fetched skin,
     * a file on disk — sharing the same LRU + cleanup as [resource]. The caller owns
     * fetching the bytes; decode is deferred to first draw.
     */
    fun fromEncoded(key: String, bytes: ByteArray): Image = resourceCache.getOrPut(key) {
        Image.makeDeferredFromEncodedBytes(bytes)
    }

    /**
     * Decodes an animated picture (GIF, animated WebP) under [key], cached like [fromEncoded].
     * A still image is a legitimate one-frame animation, so this works for any format Skia reads.
     *
     * Unlike [fromEncoded] the decode is not deferred — reading the frame table is what tells the
     * animation how long it is — so hand it bytes you already have.
     */
    fun animated(key: String, bytes: ByteArray): SkijaAnimation = animationCache.getOrPut(key) {
        val data = Data.makeFromBytes(bytes)
        val codec = try {
            Codec.makeFromData(data)
        } catch (e: IllegalArgumentException) {
            data.close()
            throw IllegalArgumentException("BlackSkija: not a picture Skia can decode: $key", e)
        }
        // The codec holds its own reference to the bytes; ours has done its job.
        data.close()
        SkijaAnimation(codec)
    }

    /** Drops a cached [resource], [fromEncoded] or [animated] entry and frees it after the frame. */
    fun delete(path: String) {
        resourceCache.remove(path)?.let { DeferredFree.later(it) }
        animationCache.remove(path)?.let { DeferredFree.later(it) }
    }

    /**
     * Draws the Minecraft texture registered under [id] (resource-pack aware) into the destination
     * rect. Borrows the GPU texture (cached; the borrow samples live, so pack changes show with no
     * latency). No-op if the texture has no GPU view yet.
     */
    fun drawMc(
        id: Identifier, x: Number, y: Number, w: Number, h: Number,
        radius: Number = 0, tint: Color? = null,
    ) {
        // getTextureView() throws (not null) until the texture is uploaded; treat that as a no-op.
        val view = runCatching { Minecraft.getInstance().textureManager.getTexture(id).textureView }.getOrNull() ?: return
        val image = SkijaTextures.wrap(view, premultiplied = false) ?: return
        Skija.image(image, x, y, w, h, radius, tint)
    }

    /**
     * Draws a single sprite from a stitched [TextureAtlas] (e.g. [TextureAtlas.LOCATION_BLOCKS]),
     * cropped to the sprite's region. [spriteId] is the content id, e.g. `minecraft:block/stone`
     * (no `textures/` prefix, no `.png`). No-op if the atlas/sprite/view isn't available.
     */
    fun drawMcSprite(
        atlasId: Identifier, spriteId: Identifier,
        x: Number, y: Number, w: Number, h: Number, radius: Number = 0, tint: Color? = null,
    ) {
        val atlas = Minecraft.getInstance().textureManager.getTexture(atlasId) as? TextureAtlas ?: return
        val sprite = atlas.getSprite(spriteId)
        // textureView throws until the atlas is stitched/uploaded; no-op until then.
        val view = runCatching { atlas.textureView }.getOrNull() ?: return
        val image = SkijaTextures.wrap(view, premultiplied = false) ?: return
        val tw = image.width.toFloat()
        val th = image.height.toFloat()
        Skija.image(
            image,
            sprite.u0 * tw, sprite.v0 * th, (sprite.u1 - sprite.u0) * tw, (sprite.v1 - sprite.v0) * th,
            x, y, w, h, radius, tint,
        )
    }
}
