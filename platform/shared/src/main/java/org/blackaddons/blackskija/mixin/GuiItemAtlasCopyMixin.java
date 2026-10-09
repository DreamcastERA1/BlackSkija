package org.blackaddons.blackskija.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Animated icon capture requires copying the item-atlas color texture before slot recycling. */
@Mixin(GuiItemAtlas.class)
public class GuiItemAtlasCopyMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", ordinal = 0), index = 1)
    private int blackskija$copyableItemAtlas(int usage) { return usage | GpuTexture.USAGE_COPY_SRC; }
}
