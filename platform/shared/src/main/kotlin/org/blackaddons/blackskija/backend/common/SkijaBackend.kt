package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface

internal interface SkijaBackend {

    val context: DirectContext

    val displayName: String

    fun onBeginFrame() {}
    fun saveState() {}
    fun restoreState() {}

    // Orders this frame's write into view before MC's blit read, so we can blit the buffer drawn this
    // frame (0 latency). Vulkan submits a pipeline barrier; GL is already ordered, so the no-op fits.
    fun orderWriteBeforeRead(view: GpuTextureView) {}

    val flipBlitU: Boolean get() = false
    val flipBlitV: Boolean get() = false

    fun wrapTarget(view: GpuTextureView): Surface
    fun wrapTexture(view: GpuTextureView, premultiplied: Boolean = false): Image?
    fun dispose()
}
