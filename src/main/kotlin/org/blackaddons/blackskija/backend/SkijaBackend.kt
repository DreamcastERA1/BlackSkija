package org.blackaddons.blackskija.backend

import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vulkan.VulkanDevice
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface

interface SkijaBackend {

    val context: DirectContext

    fun onBeginFrame() {}
    fun saveState() {}
    fun restoreState() {}

    val flipBlitU: Boolean get() = false
    val flipBlitV: Boolean get() = false

    fun wrapTarget(view: GpuTextureView): Surface
    fun wrapTexture(view: GpuTextureView, premultiplied: Boolean = false): Image?
    fun dispose()

    companion object {
        val active: SkijaBackend?
            get() = when (RenderSystem.getDevice().backend) {
                is VulkanDevice -> VulkanSkijaBackend
                is GlDevice -> GlSkijaBackend
                else -> null
            }
    }
}
