package org.blackaddons.blackskija.api

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.Image

interface TextureBackend {

    fun wrapTexture(
        view: GpuTextureView,
        alphaType: ColorAlphaType,
    ): Image?
}