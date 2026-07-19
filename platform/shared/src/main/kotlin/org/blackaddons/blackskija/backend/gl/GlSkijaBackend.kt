package org.blackaddons.blackskija.backend.gl

import com.mojang.blaze3d.opengl.GlTextureView
import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.*
import org.blackaddons.blackskija.backend.common.SkijaBackend
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL30C

internal object GlSkijaBackend : SkijaBackend {

    private const val GL_RGBA8 = 0x8058

    override val displayName = "OpenGL"

    private var cached: DirectContext? = null
    override val context: DirectContext
        get() = cached ?: DirectContext.makeGL().also { cached = it }

    // FBOs wrapping our UI targets. On resize the new one is created before the old is released, so
    // keep a small ring (2) and delete the oldest past that.
    private val fbos = ArrayDeque<Int>()

    override val flipBlitU = false
    override val flipBlitV = true

    override fun onBeginFrame() {
        context.resetGLAll()
    }

    // Skija's raw GL calls bypass MC's state cache; snapshot/restore so it stays truthful, else MC
    // renders the item atlas on Skija's leftover state (the GL dark-item bug).
    override fun saveState() = GlStateGuard.save()
    override fun restoreState() = GlStateGuard.restore()

    override fun wrapTarget(view: GpuTextureView): Surface {
        val glId = GlTextures.id(view as GlTextureView)
        val w = view.getWidth(0)
        val h = view.getHeight(0)

        val prevFbo = GL30C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING)
        val fbo = GL30C.glGenFramebuffers()
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, glId, 0)
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, prevFbo)
        fbos.addLast(fbo)
        while (fbos.size > 2) GL30C.glDeleteFramebuffers(fbos.removeFirst())

        val rt = BackendRenderTarget.makeGL(w, h, 0, 0, fbo, GL_RGBA8)

        return Surface.wrapBackendRenderTarget(
            context, rt, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888, null,
        )
    }

    override fun wrapTexture(view: GpuTextureView, premultiplied: Boolean): Image? {
        val glId = (view as? GlTextureView)?.let { GlTextures.id(it) } ?: return null
        val info = GLTextureInfo(GL11C.GL_TEXTURE_2D, glId, GL_RGBA8)
        val backend = BackendTexture.makeGL(view.getWidth(0), view.getHeight(0), false, info)
        val alpha = if (premultiplied) ColorAlphaType.PREMUL else ColorAlphaType.UNPREMUL
        val image = Image.borrowTextureFrom(
            context, backend, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, alpha, null, null,
        )
        backend.close()
        return image
    }

    override fun dispose() {
        while (fbos.isNotEmpty()) GL30C.glDeleteFramebuffers(fbos.removeFirst())
        cached?.close()
        cached = null
    }
}
