package org.blackaddons.blackskija.backend

import com.mojang.blaze3d.opengl.GlTextureView
import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.BackendTexture
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.GLTextureInfo
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL33C
import org.lwjgl.system.MemoryStack

object GlSkijaBackend : SkijaBackend {

    private const val GL_RGBA8 = 0x8058

    private var cached: DirectContext? = null
    override val context: DirectContext
        get() = cached ?: DirectContext.makeGL().also { cached = it }

    private val fbos = ArrayDeque<Int>()

    override val flipBlitU = false
    override val flipBlitV = true

    override fun onBeginFrame() {
        context.resetGLAll()
    }

    override fun wrapTarget(view: GpuTextureView): Surface {
        val glId = (view as GlTextureView).glId()
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
        val glId = (view as? GlTextureView)?.glId() ?: return null
        val info = GLTextureInfo(GL11C.GL_TEXTURE_2D, glId, GL_RGBA8)
        val backend = BackendTexture.makeGL(view.getWidth(0), view.getHeight(0), false, info)
        val alpha = if (premultiplied) ColorAlphaType.PREMUL else ColorAlphaType.UNPREMUL
        val image = Image.borrowTextureFrom(
            context, backend, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, alpha, null, null,
        )
        backend.close()
        return image
    }


    private const val TEX_UNITS = 12

    private var sDrawFbo = 0
    private var sReadFbo = 0
    private var sProgram = 0
    private var sVao = 0
    private var sArrayBuffer = 0
    private var sActiveTexture = GL13C.GL_TEXTURE0
    private val sTexBinding = IntArray(TEX_UNITS)
    private val sSamplerBinding = IntArray(TEX_UNITS)
    private var sBlend = false
    private var sBlendSrcRgb = 0
    private var sBlendDstRgb = 0
    private var sBlendSrcAlpha = 0
    private var sBlendDstAlpha = 0
    private var sBlendEqRgb = 0
    private var sBlendEqAlpha = 0
    private var sDepthTest = false
    private var sDepthFunc = 0
    private var sDepthMask = false
    private var sCull = false
    private var sScissor = false
    private val sScissorBox = IntArray(4)
    private val sColorMask = BooleanArray(4)
    private var sFramebufferSrgb = false

    override fun saveState() {
        sDrawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
        sReadFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING)
        sProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
        sVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)
        sArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING)
        sActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)
        for (i in 0 until TEX_UNITS) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i)
            sTexBinding[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)
            sSamplerBinding[i] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING)
        }
        GL13C.glActiveTexture(sActiveTexture)

        sBlend = GL11C.glIsEnabled(GL11C.GL_BLEND)
        sBlendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB)
        sBlendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB)
        sBlendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA)
        sBlendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA)
        sBlendEqRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB)
        sBlendEqAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA)

        sDepthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST)
        sDepthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)
        sDepthMask = GL11C.glGetInteger(GL11C.GL_DEPTH_WRITEMASK) != 0
        sCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE)
        sScissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST)
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, sScissorBox)
        sFramebufferSrgb = GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB)

        MemoryStack.stackPush().use { stack ->
            val buf = stack.malloc(4)
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, buf)
            for (i in 0 until 4) sColorMask[i] = buf.get(i).toInt() != 0
        }
    }

    override fun restoreState() {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sDrawFbo)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sReadFbo)
        GL20C.glUseProgram(sProgram)
        GL30C.glBindVertexArray(sVao)
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, sArrayBuffer)
        for (i in 0 until TEX_UNITS) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i)
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, sTexBinding[i])
            GL33C.glBindSampler(i, sSamplerBinding[i])
        }
        GL13C.glActiveTexture(sActiveTexture)

        setEnabled(GL11C.GL_BLEND, sBlend)
        GL14C.glBlendFuncSeparate(sBlendSrcRgb, sBlendDstRgb, sBlendSrcAlpha, sBlendDstAlpha)
        GL20C.glBlendEquationSeparate(sBlendEqRgb, sBlendEqAlpha)

        setEnabled(GL11C.GL_DEPTH_TEST, sDepthTest)
        GL11C.glDepthFunc(sDepthFunc)
        GL11C.glDepthMask(sDepthMask)
        setEnabled(GL11C.GL_CULL_FACE, sCull)
        setEnabled(GL11C.GL_SCISSOR_TEST, sScissor)
        GL11C.glScissor(sScissorBox[0], sScissorBox[1], sScissorBox[2], sScissorBox[3])
        setEnabled(GL30C.GL_FRAMEBUFFER_SRGB, sFramebufferSrgb)
        GL11C.glColorMask(sColorMask[0], sColorMask[1], sColorMask[2], sColorMask[3])
    }

    private fun setEnabled(cap: Int, on: Boolean) {
        if (on) GL11C.glEnable(cap) else GL11C.glDisable(cap)
    }

    override fun dispose() {
        while (fbos.isNotEmpty()) GL30C.glDeleteFramebuffers(fbos.removeFirst())
        cached?.close()
        cached = null
    }
}
