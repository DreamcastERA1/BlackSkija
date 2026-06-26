package org.blackaddons.blackskija.backend

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Surface
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaItems
import org.joml.Matrix3x2f
import org.slf4j.LoggerFactory

object SkijaCompositor {

    private val LOG = LoggerFactory.getLogger("blackskija")

    @Volatile
    var enabled = false

    private const val UI_TEXTURE_USAGE = 0xf

    private val textures = arrayOfNulls<GpuTexture>(2)
    private val views = arrayOfNulls<GpuTextureView>(2)
    private val surfaces = arrayOfNulls<Surface>(2)
    private var texWidth = 0
    private var texHeight = 0
    private var write = 0
    private var wasEnabled = false

    /**
     * The UI to draw each frame, in gui-scaled coordinates (same space as MC's GUI), via
     * the [Skija] drawing API. Inside this callback a frame is already open — just issue
     * draw calls. Defaults to a no-op; set it to render real UI.
     */
    @Volatile
    var content: () -> Unit = {}

    fun composite(guiRenderState: GuiRenderState) {
        if (!enabled) {
            wasEnabled = false
            return
        }
        if (!SkijaNatives.ready) return
        // No supported backend (MC on a GPU API we don't adapt) -> disable, don't crash.
        val backend = SkijaBackend.active ?: run {
            enabled = false
            wasEnabled = false
            return
        }
        val mc = Minecraft.getInstance()
        val window = mc.window
        if (window.isIconified) return
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return

        backend.saveState()
        try {
            backend.onBeginFrame()
            ensureTargets(backend, w, h)
            val surface = surfaces[write] ?: return

            val firstFrame = !wasEnabled
            wasEnabled = true

            val canvas = surface.canvas
            canvas.clear(0)
            val guiScale = w.toFloat() / window.guiScaledWidth.coerceAtLeast(1)
            SkijaItems.beginFrame(guiRenderState)
            Skija.beginFrame(canvas, Matrix3x2f().scale(guiScale))
            try {
                content()
            } catch (e: Throwable) {
                LOG.error("Skija content draw failed", e)
            } finally {
                Skija.endFrame()
                SkijaItems.endFrame()
            }
            // syncCpu only on the first frame, where we sample what we just drew.
            backend.context.flushAndSubmit(surface, firstFrame)

            val blit = if (firstFrame) write else 1 - write
            val view = views[blit] ?: return
            val sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)
            // BlitRenderState float order is u0, u1, v0, v1. Flip per backend (GL FBO is bottom-left).
            val u0 = if (backend.flipBlitU) 1f else 0f
            val u1 = if (backend.flipBlitU) 0f else 1f
            val v0 = if (backend.flipBlitV) 1f else 0f
            val v1 = if (backend.flipBlitV) 0f else 1f
            guiRenderState.addBlitToCurrentLayer(
                BlitRenderState(
                    RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                    TextureSetup.singleTexture(view, sampler),
                    Matrix3x2f(),
                    0, 0, window.guiScaledWidth, window.guiScaledHeight,
                    u0, u1, v0, v1,
                    -1,
                    null,
                ),
            )

            write = 1 - write
        } catch (t: Throwable) {
            LOG.error("BlackSkija compositing failed — disabling overlay", t)
            enabled = false
        } finally {
            backend.restoreState()
        }
    }

    /**
     * (Re)creates both UI textures, views and Skija [Surface]s only when missing or the
     * window resized. Both buffers are primed transparent, so the first deferred blit
     * isn't garbage.
     */
    private fun ensureTargets(backend: SkijaBackend, w: Int, h: Int) {
        if (surfaces[0] != null && texWidth == w && texHeight == h) return
        wasEnabled = false
        closeAll()
        val device = RenderSystem.getDevice()
        for (i in 0..1) {
            val tex = device.createTexture("blackskija ui $i", UI_TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1)
            val view = device.createTextureView(tex)
            val surface = backend.wrapTarget(view)
            surface.canvas.clear(0)
            backend.context.flushAndSubmit(surface, true) // prime once
            textures[i] = tex
            views[i] = view
            surfaces[i] = surface
        }
        texWidth = w
        texHeight = h
        write = 0
    }

    private fun closeAll() {
        for (i in 0..1) {
            surfaces[i]?.close()
            views[i]?.close()
            textures[i]?.close()
            surfaces[i] = null
            views[i] = null
            textures[i] = null
        }
    }

    /**
     * Releases all GPU resources (buffers first, then the Skija context). Wired to client
     * shutdown; guarded so a teardown-order quirk can't crash process exit.
     */
    fun shutdown() {
        try {
            closeAll()
            texWidth = 0
            texHeight = 0
            write = 0
            SkijaBackend.active?.dispose()
        } catch (e: Throwable) {
            LOG.warn("Skija shutdown cleanup failed", e)
        }
    }
}
