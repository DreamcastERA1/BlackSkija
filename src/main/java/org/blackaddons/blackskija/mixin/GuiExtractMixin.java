package org.blackaddons.blackskija.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.blackaddons.blackskija.backend.common.SkijaCompositor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks where the HUD's Skija draws belong in the GUI's depth order.
 *
 * {@code Gui.extractRenderState} extracts the HUD, then the overlay, then the screen — the last two
 * each opening a new stratum. Injecting after the HUD call returns (rather than at the tail of
 * {@code Hud.extractRenderState} itself) puts this after every mixin a consumer may have on the HUD,
 * so their draws are all queued before the boundary is recorded.
 */
@Mixin(Gui.class)
public class GuiExtractMixin {

    @Shadow
    @Final
    private GuiRenderState guiRenderState;

    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Hud;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void blackskija$markHudLayer(CallbackInfo ci) {
        SkijaCompositor.INSTANCE.markHudLayer(this.guiRenderState);
    }
}
