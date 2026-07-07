package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Image
import org.blackaddons.blackskija.api.TextureBackend

// Backend-side implementation of the api's TextureBackend seam. Registered into SkijaTextures.backend
// at init so api draws MC textures without importing the backend package.
internal object SkijaBackendBridge : TextureBackend {
    override fun wrapTexture(view: GpuTextureView, premultiplied: Boolean): Image? =
        WrappedTextureCache.get(view, premultiplied)
}
