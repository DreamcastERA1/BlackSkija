package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.systems.GpuDevice
import org.blackaddons.blackskija.backend.gl.GlSkijaBackend

// 26.1.2 has no Vulkan backend, so GL is the only match.
internal object SkijaBackendResolver {
    fun resolve(device: GpuDevice): SkijaBackend? =
        if (device.backend is GlDevice) GlSkijaBackend else null
}
