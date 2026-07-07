package org.blackaddons.blackskija.backend.gl

import org.lwjgl.opengl.*
import org.lwjgl.system.MemoryStack

// Snapshots/restores the raw GL state Skija clobbers, so MC's GlStateManager cache stays truthful.
// Skija's raw gl* calls bypass that cache, so without this MC renders on Skija's leftovers (the GL
// dark-item bug). save() runs before any Skija work, restore() after. Vulkan needs none of this.
internal object GlStateGuard {

    // Enough texture units for MC 26.2's GUI pipeline; bump if a future MC binds higher units.
    private const val TEX_UNITS = 12

    private var sDrawFbo = 0
    private var sReadFbo = 0
    private var sProgram = 0
    private var sVao = 0
    private var sArrayBuffer = 0
    private var sElementBuffer = 0
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

    fun save() {
        sDrawFbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
        sReadFbo = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING)
        sProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
        sVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)
        sArrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING)
        // EBO binding is VAO-scoped (read while MC's VAO is bound); restored after we rebind it.
        sElementBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING)
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

    fun restore() {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, sDrawFbo)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sReadFbo)
        GL20C.glUseProgram(sProgram)
        GL30C.glBindVertexArray(sVao)
        // After the VAO is bound, restore its EBO binding (binding EBO mutates the bound VAO).
        GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, sElementBuffer)
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
}
