package org.blackaddons.blackskija.mixin;

import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import org.blackaddons.blackskija.api.draw.SkijaItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiItemCaptureMixin {

    @Inject(method = "submitBlitFromItemAtlas", at = @At("HEAD"), cancellable = true)
    private void blackskija$captureItem(GuiItemRenderState itemState, GuiItemAtlas.SlotView slotView, CallbackInfo ci) {
        if (SkijaItems.INSTANCE.capture(itemState, slotView.textureView(), slotView.u0(), slotView.v0(), slotView.u1(), slotView.v1())) {
            ci.cancel();
        }
    }
}
