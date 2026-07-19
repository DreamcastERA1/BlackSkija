package org.blackaddons.blackskija.backend.gl

import com.mojang.blaze3d.opengl.GlTextureView

// 26.1.2's GlTextureView has no glId(); the handle is on the backing GlTexture.
internal object GlTextures {
    fun id(view: GlTextureView): Int = view.texture().glId()
}
