package org.blackaddons.blackskija.backend.gl

import com.mojang.blaze3d.opengl.GlTextureView

// The raw GL texture handle lives directly on the view in 26.2; earlier versions reach it through the
// texture. One accessor, so GlSkijaBackend stays version-neutral.
internal object GlTextures {
    fun id(view: GlTextureView): Int = view.glId()
}
