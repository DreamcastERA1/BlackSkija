package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.Image
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.Identifier
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaTextures
import org.blackaddons.blackskija.api.draw.SkijaImages.drawMc
import org.blackaddons.blackskija.api.draw.SkijaImages.drawMcSprite
import org.blackaddons.blackskija.api.draw.SkijaImages.resource
import java.awt.Color

/**
 * Image sources for the [Skija] draw layer:
 *  - [resource]: decode a classpath PNG/JPG into a cached Skija [Image].
 *  - [drawMc] / [drawMcSprite]: draw a live Minecraft texture (resource-pack aware) by borrowing
 *    its GPU handle, no CPU copy.
 */
object SkijaImages {

    private const val RESOURCE_CACHE_MAX = 128
    private val resourceCache = object : LinkedHashMap<String, Image>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Image>): Boolean {
            if (size > RESOURCE_CACHE_MAX) { runCatching { eldest.value.close() }; return true }
            return false
        }
    }

    /** Decodes a classpath PNG/JPG into a cached Skija [Image] (e.g. `/assets/.../x.png`). */
    fun resource(path: String): Image = resourceCache.getOrPut(path) {
        val bytes = SkijaImages::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("BlackSkija: image not found on classpath: $path")
        Image.makeDeferredFromEncodedBytes(bytes)
    }

    /** Drops a cached [resource] image and frees it. */
    fun delete(path: String) {
        resourceCache.remove(path)?.close()
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
