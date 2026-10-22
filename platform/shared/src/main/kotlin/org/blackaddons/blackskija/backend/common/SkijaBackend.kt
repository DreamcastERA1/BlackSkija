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

    /* Readies [targets] and the borrowed [sampled] textures for Skia before its submit; Vulkan moves them out of Minecraft's layout. */
    fun acquire(targets: List<GpuTextureView>, sampled: Collection<GpuTextureView>) {}

    /* Hands back what acquire took, ordered before MC's blit reads, so we can blit the buffers drawn this frame (0 latency). */
    fun release() {}

    // Hands the frame's flushed work to the GPU, once per frame. Vulkan needs it every frame for the blit to see it.
    fun submit() {
        context.submit(false)
    }

    val flipBlitU: Boolean get() = false
    val flipBlitV: Boolean get() = false

    fun wrapTarget(view: GpuTextureView): Surface

    /* Frees what [wrapTarget] made for [surface]. Call it before closing the surface. */
    fun releaseTarget(surface: Surface) {}

    fun wrapTexture(view: GpuTextureView, premultiplied: Boolean = false): Image?
    fun dispose()
}
