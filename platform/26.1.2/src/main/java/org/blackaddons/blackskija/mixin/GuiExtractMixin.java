package org.blackaddons.blackskija.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.blackaddons.blackskija.backend.common.SkijaCompositor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiExtractMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void blackskija$markHudLayer(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        SkijaCompositor.INSTANCE.markHudLayer(extractor.guiRenderState);
    }
}
