package org.blackaddons.blackskija.backend.common

import org.blackaddons.blackskija.compat.GpuTextureView
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface

internal interface SkijaBackend {

    val context: DirectContext

    val displayName: String

    fun onBeginFrame() {}
    fun saveState() {}
    fun restoreState() {}

    // Orders this frame's writes into views before MC's blit reads, so we can blit the buffers drawn this
    // frame (0 latency). Vulkan submits one pipeline barrier for all of them; GL is already ordered.
    fun orderWriteBeforeRead(views: List<GpuTextureView>) {}

    // Hands the frame's flushed work to the GPU, once per frame. Vulkan needs it every frame for the blit to see it.
    fun submit() {
        context.submit(false)
    }

    val flipBlitU: Boolean get() = false
    val flipBlitV: Boolean get() = false

    fun wrapTarget(view: GpuTextureView): Surface
    fun wrapTexture(view: GpuTextureView, premultiplied: Boolean = false): Image?
    fun dispose()
}
