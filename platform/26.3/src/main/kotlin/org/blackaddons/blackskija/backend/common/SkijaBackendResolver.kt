package org.blackaddons.blackskija.backend.common

import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.backend.opengl.GlDevice
import com.mojang.renderpearl.backend.vulkan.VulkanDevice
import com.mojang.renderpearl.frontend.FrontendGpuDevice
import org.blackaddons.blackskija.backend.gl.GlSkijaBackend
import org.blackaddons.blackskija.backend.vulkan.VulkanSkijaBackend

// 26.3 ships both GPU backends, so both branches are live.
internal object SkijaBackendResolver {
    fun resolve(device: GpuDevice): SkijaBackend? = when ((device as? FrontendGpuDevice)?.backend) {
        is VulkanDevice -> VulkanSkijaBackend
        is GlDevice -> GlSkijaBackend
        else -> null
    }
}
