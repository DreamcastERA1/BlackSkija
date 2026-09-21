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
import java.lang.ref.WeakReference

/**
 * Image sources for the [Skija] draw layer:
 *  - [resource]: decode a classpath PNG/JPG into a cached Skija [Image].
 *  - [animated]: a GIF, animated WebP or APNG as a [SkijaAnimation], a frame at a time.
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

    // The animations in play. Falling out of here frees nothing — an animation is stateful, so the
    // caller that kept the handle owns it just as much as this cache does, and closing on eviction
    // blanked pictures that were still on screen: hold 17 distinct GIFs and the first one died mid-
    // play. A demoted animation moves to [cooledAnimations] and keeps running for whoever holds it.
    private val animationCache = object : LinkedHashMap<String, SkijaAnimation>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkijaAnimation>): Boolean {
            if (size <= ANIMATION_CACHE_MAX) return false
            cooledAnimations[eldest.key] = WeakReference(eldest.value)
            return true
        }
    }

    // Animations this cache no longer holds open. Whoever still has the handle keeps playing, and one
    // nobody holds is collected — Skija frees its natives from the cleaner, all of them CPU-side.
    private val cooledAnimations = HashMap<String, WeakReference<SkijaAnimation>>()

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
     * Decodes an animated picture (GIF, animated WebP, APNG) under [key], cached like [fromEncoded].
     * A still image is a legitimate one-frame animation, so this works for any format Skia reads.
     *
     * The same [key] gives back the same animation, playing where it already is, for as long as
     * anyone holds it — keep the handle or ask again each frame, whichever suits. [delete] is the
     * way to be rid of one at a chosen moment; otherwise dropping every reference is enough.
     *
     * Unlike [fromEncoded] the decode is not deferred — reading the frame table is what tells the
     * animation how long it is — so hand it bytes you already have.
     */
    fun animated(key: String, bytes: ByteArray): SkijaAnimation {
        animationCache[key]?.let { if (it.isOpen) return it }
        cooledAnimations.remove(key)?.get()?.let {
            if (it.isOpen) {
                animationCache[key] = it
                return it
            }
        }
        cooledAnimations.entries.removeIf { it.value.get() == null }
        // Skia reads an APNG as a still, so that one format is split and composited ourselves.
        return SkijaAnimation(ApngFrames.of(bytes) ?: CodecFrames(codec(key, bytes)))
            .also { animationCache[key] = it }
    }

    private fun codec(key: String, bytes: ByteArray): Codec {
        val data = Data.makeFromBytes(bytes)
        val codec = try {
            Codec.makeFromData(data)
        } catch (e: IllegalArgumentException) {
            data.close()
            throw IllegalArgumentException("BlackSkija: not a picture Skia can decode: $key", e)
        }
        // The codec holds its own reference to the bytes; ours has done its job.
        data.close()
        return codec
    }

    /**
     * Drops a cached [resource], [fromEncoded] or [animated] entry and frees it after the frame.
     *
     * This is the one thing that ends an animation while a caller may still be holding it, so say it
     * only about a picture you know is finished with.
     */
    fun delete(path: String) {
        resourceCache.remove(path)?.let { DeferredFree.later(it) }
        animationCache.remove(path)?.let { DeferredFree.later(it) }
        cooledAnimations.remove(path)?.get()?.let { DeferredFree.later(it) }
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
