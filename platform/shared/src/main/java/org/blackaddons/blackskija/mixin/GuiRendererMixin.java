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
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Set;

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
    @ModifyArgs(
        method = "prepareItemElements",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;prepareItemAtlas(Ljava/util/Set;I)Lnet/minecraft/client/gui/render/GuiItemAtlas;"
        )
    )
    private void blackskija$supersampleItemAtlas(Args args) {
        Set<?> items = args.get(0);
        int wanted = SkijaItems.INSTANCE.slotTextureSize(args.<Integer>get(1));
        if (this.itemAtlas != null) {
            int built = ((GuiItemAtlasAccessor) this.itemAtlas).blackskija$slotTextureSize();
            int perSide = this.itemAtlas.textureSize() / built;
            if (SkijaItems.INSTANCE.shouldRebuildAtlas(built, wanted, perSide * perSide, items.size())) this.invalidateItemAtlas();
        }
        args.set(1, wanted);
    }
}
