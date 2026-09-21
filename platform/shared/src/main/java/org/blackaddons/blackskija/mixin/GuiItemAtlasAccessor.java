package org.blackaddons.blackskija.mixin;

import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiItemAtlas.class)
public interface GuiItemAtlasAccessor {

    @Accessor("slotTextureSize")
    int blackskija$slotTextureSize();
}
