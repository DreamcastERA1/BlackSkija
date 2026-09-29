package org.blackaddons.blackskija.backend.gl

import com.mojang.blaze3d.opengl.GlCommandEncoder
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import org.blackaddons.blackskija.mixin.GlStateManagerAccessor
import org.lwjgl.opengl.*

/**
 * Puts GL back the way Minecraft believes it is, read from Minecraft's own state cache instead of
 * queried from the driver. Every `glGet*` is a round trip to the driver thread;
 *
 * Restoring the cache rather than a pre-Skija snapshot is also what keeps that cache truthful when
 * Minecraft itself changes state mid-composite (item and entity captures). State Minecraft does not
 * cache it sets before every use (VAO, array/element buffers, samplers, scissor box, pixel unpack),
 * or never changes from the GL default (blend equation, sRGB writes), so those go back to neutral.
 * The shader program is the one exception Minecraft skips re-binding, so its record is cleared.
 */
internal object MinecraftGlState {
    const val available = true

    private val encoder by lazy {
        (RenderSystem.getDevice().backend as GlDevice).createCommandEncoder() as GlCommandEncoder
    }

    fun restore() {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, GlStateManager.writeFbo)
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, GlStateManager.readFbo)
        GL20C.glUseProgram(0)
        encoder.lastProgram = null
        GL30C.glBindVertexArray(0)
        GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0)
        GlStateManager.TEXTURES.forEachIndexed { unit, texture ->
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit)
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture.binding.coerceAtLeast(0))
            GL33C.glBindSampler(unit, 0)
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + GlStateManager.activeTexture)

        val blend = GlStateManagerAccessor.`blackskija$blend`()
        setEnabled(GL11C.GL_BLEND, blend.mode.enabled)
        GL14C.glBlendFuncSeparate(blend.srcRgb, blend.dstRgb, blend.srcAlpha, blend.dstAlpha)
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD)

        val depth = GlStateManager.DEPTH
        setEnabled(GL11C.GL_DEPTH_TEST, depth.mode.enabled)
        GL11C.glDepthFunc(depth.func)
        GL11C.glDepthMask(depth.mask)
        setEnabled(GL11C.GL_CULL_FACE, GlStateManager.CULL.enable.enabled)
        setEnabled(GL11C.GL_SCISSOR_TEST, GlStateManager.SCISSOR.mode.enabled)
        GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB)
        val mask = GlStateManagerAccessor.`blackskija$colorMask`()
        GL11C.glColorMask(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)

        GlStateGuard.neutralizeUnpack()
    }

    private fun setEnabled(cap: Int, on: Boolean) {
        if (on) GL11C.glEnable(cap) else GL11C.glDisable(cap)
    }
}
