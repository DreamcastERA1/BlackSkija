package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.vulkan.VulkanDevice
import org.blackaddons.blackskija.backend.gl.GlSkijaBackend
import org.blackaddons.blackskija.backend.vulkan.VulkanSkijaBackend

// 26.2 ships both GPU backends, so both branches are live.
internal object SkijaBackendResolver {
    fun resolve(device: GpuDevice): SkijaBackend? = when (device.backend) {
        is VulkanDevice -> VulkanSkijaBackend
        is GlDevice -> GlSkijaBackend
        else -> null
    }
}
