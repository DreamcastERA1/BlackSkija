package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface

internal interface SkijaBackend {

    val context: DirectContext

    val displayName: String

    fun onBeginFrame() {}

    fun saveState() {}

    fun restoreState() {}

    fun orderWriteBeforeRead(
        view: GpuTextureView,
    ) {
    }

    val flipBlitU: Boolean
        get() = false

    val flipBlitV: Boolean
        get() = false

    /** Whether Minecraft's main framebuffer, borrowed as a top-left image, needs a vertical flip. */
    val flipBackdropY: Boolean

    fun wrapTarget(
        view: GpuTextureView,
    ): Surface

    fun wrapTexture(
        view: GpuTextureView,
        alphaType: ColorAlphaType = ColorAlphaType.UNPREMUL,
    ): Image?

    fun dispose()
}
