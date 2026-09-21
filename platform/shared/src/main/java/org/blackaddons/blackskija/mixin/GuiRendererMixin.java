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
    }

    /**
     * Sizes the item-atlas slot for the largest item on screen, and drops an atlas built at another
     * size: {@code prepareItemAtlas} keeps any atlas that still has room and ignores the size it is
     * passed, so without the rebuild a new slot size would only land when the atlas happened to fill.
     */
    @ModifyArg(
        method = "prepareItemElements",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;prepareItemAtlas(Ljava/util/Set;I)Lnet/minecraft/client/gui/render/GuiItemAtlas;"
        ),
        index = 1
    )
    private int blackskija$supersampleItemAtlas(int slotTextureSize) {
        int wanted = SkijaItems.INSTANCE.slotTextureSize(slotTextureSize);
        if (this.itemAtlas != null) {
            int built = ((GuiItemAtlasAccessor) this.itemAtlas).blackskija$slotTextureSize();
            if (SkijaItems.INSTANCE.shouldRebuildAtlas(built, wanted)) this.invalidateItemAtlas();
        }
        return wanted;
    }
}
