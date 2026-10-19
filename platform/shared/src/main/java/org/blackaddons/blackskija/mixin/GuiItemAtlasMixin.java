package org.blackaddons.blackskija.mixin;

import net.minecraft.client.gui.render.GuiItemAtlas;
import org.blackaddons.blackskija.api.draw.SkijaItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiItemAtlas.class)
public class GuiItemAtlasMixin {

    @Inject(method = "close", at = @At("HEAD"))
    private void blackskija$dropDeferredEndFrame(CallbackInfo ci) {
        SkijaItems.INSTANCE.atlasClosed((GuiItemAtlas) (Object) this);
    }
}
