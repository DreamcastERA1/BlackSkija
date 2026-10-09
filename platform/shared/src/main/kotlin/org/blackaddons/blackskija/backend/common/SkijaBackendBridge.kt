package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.Image
import org.blackaddons.blackskija.api.TextureBackend

internal object SkijaBackendBridge : TextureBackend {

    override fun wrapTexture(
        view: GpuTextureView,
        alphaType: ColorAlphaType,
    ): Image? {
        return WrappedTextureCache.get(
            view,
            alphaType,
        )
    }
}