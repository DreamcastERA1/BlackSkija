package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture

// The colour-target format enum moved packages between versions (26.2 GpuFormat, earlier TextureFormat),
// so texture creation is the one device call that can't sit in shared code.
internal object UiTexture {
    fun create(name: String, usage: Int, width: Int, height: Int): GpuTexture =
        RenderSystem.getDevice().createTexture(name, usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1)
}
