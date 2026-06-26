package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Image
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.Identifier
import org.blackaddons.blackskija.backend.SkijaBackend
import java.awt.Color

/**
 * Image sources for the [Skija] draw layer:
 *  - [resource]: decode a bundled/classpath PNG/JPG into a cached Skija [Image].
 *  - [drawMc] / [drawMcSprite]: draw a live Minecraft texture (resource-pack aware) by
 *    borrowing its GPU handle, no CPU copy.
 */
object SkijaImages {

    private val resourceCache = HashMap<String, Image>()

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
     * Draws the Minecraft texture registered under [id] (resolved through the texture
     * manager, so it reflects the active resource pack) into the destination rect.
     * Borrows the GPU texture, draws, and releases the wrapper each call. No-op if the
     * texture has no GPU view yet. Must be called inside a Skija frame.
     */
    fun drawMc(
        id: Identifier, x: Number, y: Number, w: Number, h: Number,
        radius: Number = 0, tint: Color? = null,
    ) {
        val view = Minecraft.getInstance().textureManager.getTexture(id).textureView
        val image = SkijaBackend.active?.wrapTexture(view) ?: return
        image.use { image ->
            Skija.image(image, x, y, w, h, radius, tint)
        }
    }

    /**
     * Draws a single sprite from a stitched [TextureAtlas] (e.g.
     * [TextureAtlas.LOCATION_BLOCKS]) — crops the atlas to just that sprite's region,
     * resource-pack-aware. [spriteId] is the content id, e.g. `minecraft:block/stone`
     * (no `textures/` prefix, no `.png`). No-op if the atlas/sprite/view isn't available.
     */
    fun drawMcSprite(
        atlasId: Identifier, spriteId: Identifier,
        x: Number, y: Number, w: Number, h: Number, radius: Number = 0, tint: Color? = null,
    ) {
        val atlas = Minecraft.getInstance().textureManager.getTexture(atlasId) as? TextureAtlas ?: return
        val sprite = atlas.getSprite(spriteId)
        val view = atlas.textureView
        val image = SkijaBackend.active?.wrapTexture(view) ?: return
        image.use { img ->
            val tw = img.width.toFloat()
            val th = img.height.toFloat()
            Skija.image(
                img,
                sprite.u0 * tw, sprite.v0 * th, (sprite.u1 - sprite.u0) * tw, (sprite.v1 - sprite.v0) * th,
                x, y, w, h, radius, tint,
            )
        }
    }
}
