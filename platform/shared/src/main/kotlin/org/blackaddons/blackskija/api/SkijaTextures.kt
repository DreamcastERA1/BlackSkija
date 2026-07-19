package org.blackaddons.blackskija.api

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Image
import org.blackaddons.blackskija.api.SkijaTextures.wrap

/**
 * Holds the active [TextureBackend]. The backend registers itself at init; the image draw paths
 * call [wrap]. Null (no-op wraps) until a backend registers, so `api` degrades gracefully alone.
 */
object SkijaTextures {

    @Volatile
    var backend: TextureBackend? = null

    fun wrap(view: GpuTextureView, premultiplied: Boolean): Image? = backend?.wrapTexture(view, premultiplied)
}