package org.blackaddons.blackskija.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The two {@link GlStateManager} caches whose shape differs between versions, so the shared access
 * widener cannot name them. Here there is one blend state and one colour mask; 26.2 keeps both per
 * colour attachment.
 */
@Mixin(GlStateManager.class)
public interface GlStateManagerAccessor {
    @Accessor("BLEND")
    static GlStateManager.BlendState blackskija$blend() {
        throw new AssertionError();
    }

    @Accessor("COLOR_MASK")
    static int blackskija$colorMask() {
        throw new AssertionError();
    }
}
