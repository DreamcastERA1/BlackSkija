package org.blackaddons.blackskija.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The two {@link GlStateManager} caches whose shape differs between versions, so the shared access
 * widener cannot name them. 26.3 has one blend state but a colour mask per attachment, which
 * MinecraftGlState does not restore yet; this exists so the shared mixin config resolves.
 */
@Mixin(GlStateManager.class)
public interface GlStateManagerAccessor {
    @Accessor("BLEND")
    static GlStateManager.BlendState blackskija$blend() {
        throw new AssertionError();
    }

    @Accessor("COLOR_MASK")
    static int[] blackskija$colorMask() {
        throw new AssertionError();
    }
}
