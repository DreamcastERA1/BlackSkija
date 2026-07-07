package org.blackaddons.blackskija.api

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Image

/**
 * Seam for wrapping a Minecraft GPU texture as a Skija [Image]. Implemented by the backend and
 * registered into [SkijaTextures], so the draw API can sample MC textures without depending on
 * the backend package.
 */
interface TextureBackend {

    /**
     * Borrows [view] as a Skija [Image] with the given [premultiplied] alpha. The result is a live
     * borrow (not a snapshot) owned by the implementation; never close it. Null if it can't be wrapped.
     */
    fun wrapTexture(view: GpuTextureView, premultiplied: Boolean): Image?
}
