package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft
import org.blackaddons.blackskija.mixin.GameRendererAccessor

object MainRenderTargetAccess {

    fun get(): RenderTarget {
        return (Minecraft.getInstance().gameRenderer as GameRendererAccessor)
            .`blackskija$getMainRenderTarget`()
    }
}
