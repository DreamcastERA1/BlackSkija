package org.blackaddons.blackskija.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
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
 * <p>This has to run after every mixin a consumer may have on the HUD, so their draws are all queued
 * before the boundary is recorded — otherwise {@code markHudLayer} sees an empty batch and the HUD
 * composites on top of the screens instead of into its own layer.
 *
 * <p>That ordering is bought structurally, by injecting into the <em>caller</em> after the HUD
 * extraction returns: a consumer hooking the HUD is inside the callee, so it cannot lose the race.
 * 26.2 gets this by injecting after {@code Gui.extractRenderState}'s call to
 * {@code Hud.extractRenderState}; here {@code Hud} is still merged into {@code Gui}, so the same trick
 * moves one level up, to {@code GameRenderer.extractGui}'s single call to
 * {@code Gui.extractRenderState}. Injecting at the tail of {@code Gui.extractRenderState} itself would
 * put us in the very place consumers hook, making the order undefined.
 */
@Mixin(GameRenderer.class)
public class GuiExtractMixin {

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(
            method = "extractGui",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void blackskija$markHudLayer(CallbackInfo ci) {
        SkijaCompositor.INSTANCE.markHudLayer(this.gameRenderState.guiRenderState);
    }
}
