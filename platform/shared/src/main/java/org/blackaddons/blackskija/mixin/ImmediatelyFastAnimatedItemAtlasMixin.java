package org.blackaddons.blackskija.mixin;

import net.minecraft.client.gui.render.GuiItemAtlas;
import org.blackaddons.blackskija.api.draw.SkijaItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ImmediatelyFast keeps animated items in an atlas of its own, built at vanilla's slot size; these
 * hooks give it the same supersampled slot as the vanilla atlas so both look alike.
 */
@Pseudo
@Mixin(targets = "net.raphimc.immediatelyfast.feature.batch_animated_item_updates.AnimatedItemAtlas", remap = false)
public class ImmediatelyFastAnimatedItemAtlasMixin {

    @ModifyVariable(method = "computeTextureSizeFor", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false, require = 0)
    private static int blackskija$supersampleTextureSize(int vanillaSlot) {
        return SkijaItems.INSTANCE.slotTextureSize(vanillaSlot);
    }

    @ModifyArg(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiItemAtlas;<init>(Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;II)V"),
        index = 2, remap = false, require = 0
    )
    private static int blackskija$supersampleSlot(int vanillaSlot) {
        return SkijaItems.INSTANCE.slotTextureSize(vanillaSlot);
    }

    @Inject(method = "endFrame", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void blackskija$deferEndFrame(CallbackInfo ci) {
        if (SkijaItems.INSTANCE.deferAtlasEndFrame((GuiItemAtlas) (Object) this)) ci.cancel();
    }
}
