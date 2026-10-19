package org.blackaddons.blackskija.mixin;

import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.blackaddons.blackskija.api.draw.SkijaItems;
import org.blackaddons.blackskija.backend.common.SkijaCompositor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

    @Shadow
    @Final
    private GuiRenderState renderState;

    @Shadow
    private GuiItemAtlas itemAtlas;

    @Shadow
    private void invalidateItemAtlas() {}

    @Inject(method = "render", at = @At("HEAD"))
    private void blackskija$composite(CallbackInfo ci) {
        SkijaCompositor.INSTANCE.composite(this.renderState);
        SkijaItems.INSTANCE.replayAtlasEndFrame();
        if (SkijaItems.INSTANCE.shouldRebuildAtlases(this.itemAtlas, this.renderState.getItemModelIdentities().size())) {
            this.invalidateItemAtlas();
        }
    }

    /**
     * Builds the item atlas at the slot size for the largest item on screen. Resizing an existing
     * atlas is decided in {@link #blackskija$composite}, before ImmediatelyFast prepares its animated
     * atlas: {@code invalidateItemAtlas} drops that one too, which mid-prepare would lose this frame's.
     */
    @ModifyArg(
        method = "prepareItemElements",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;prepareItemAtlas(Ljava/util/Set;I)Lnet/minecraft/client/gui/render/GuiItemAtlas;"
        ),
        index = 1
    )
    private int blackskija$supersampleItemAtlas(int vanillaSlot) {
        return SkijaItems.INSTANCE.atlasSlotSize(vanillaSlot);
    }
}
