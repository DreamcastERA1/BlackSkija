package org.blackaddons.blackskija.api

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.Image

object SkijaTextures {

    @Volatile
    var backend: TextureBackend? = null

    fun wrap(
        view: GpuTextureView,
        premultiplied: Boolean,
    ): Image? {
        val alphaType =
            if (premultiplied) {
                ColorAlphaType.PREMUL
            } else {
                ColorAlphaType.UNPREMUL
            }

        return backend?.wrapTexture(
            view,
            alphaType,
        )
    }

    fun wrapOpaque(
        view: GpuTextureView,
    ): Image? {
        return backend?.wrapTexture(
            view,
            ColorAlphaType.OPAQUE,
        )
    }
}