package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vulkan.VulkanDevice
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface
import org.blackaddons.blackskija.backend.gl.GlSkijaBackend
import org.blackaddons.blackskija.backend.vulkan.VulkanSkijaBackend

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

    companion object {
        val activeName: String get() = active?.displayName ?: "—"

        // GPU API is fixed for the run, so resolve once. Only cache a non-null result, so a device
        // that isn't queryable yet on first touch retries next time instead of latching disabled.
        private var cached: SkijaBackend? = null

        val active: SkijaBackend?
            get() {
                cached?.let { return it }
                val resolved = when (RenderSystem.getDevice().backend) {
                    is VulkanDevice -> VulkanSkijaBackend
                    is GlDevice -> GlSkijaBackend
                    else -> null
                }
                if (resolved != null) cached = resolved
                return resolved
            }
    }
}
