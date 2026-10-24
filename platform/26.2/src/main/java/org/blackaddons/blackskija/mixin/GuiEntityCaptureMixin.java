package org.blackaddons.blackskija.mixin;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.blackaddons.blackskija.api.draw.SkijaEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link SkijaEntity} grab a GUI entity's rendered picture-in-picture texture instead of
 * blitting it below the Skija overlay. Runs for every PiP type (entity, book, banner…); the capture
 * only claims the render states SkijaEntity registered, so everything else blits as usual.
 */
@Mixin(PictureInPictureRenderer.class)
public class GuiEntityCaptureMixin {

    @Shadow
    private GpuTextureView textureView;

    @Inject(method = "blitTexture", at = @At("HEAD"), cancellable = true)
    private void blackskija$captureEntity(PictureInPictureRenderState renderState, GuiRenderState guiRenderState, CallbackInfo ci) {
        if (SkijaEntity.INSTANCE.capture(renderState, this.textureView)) {
            ci.cancel();
        }
    }
}
