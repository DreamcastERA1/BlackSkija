package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft

object MainRenderTargetAccess {
    fun get(): RenderTarget = Minecraft.getInstance().mainRenderTarget
}
