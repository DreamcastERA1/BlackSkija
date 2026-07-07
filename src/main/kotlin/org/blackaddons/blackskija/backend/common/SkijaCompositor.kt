package org.blackaddons.blackskija.backend.common

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
import org.blackaddons.blackskija.api.draw.SkijaItems
import org.blackaddons.blackskija.api.screen.SkijaOverlay
import org.blackaddons.blackskija.backend.natives.SkijaNatives
import org.joml.Matrix3x2f
import org.slf4j.LoggerFactory

object SkijaCompositor {

    private val LOG = LoggerFactory.getLogger("blackskija")

    private const val UI_TEXTURE_USAGE = 0xf

    // Single UI buffer: we draw into it and blit it in the same frame (0 latency). Ordering between
    // our write and MC's blit comes from SkijaBackend.orderWriteBeforeRead, so no double buffer is needed.
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    private var surface: Surface? = null
    private var texWidth = 0
    private var texHeight = 0
    private var warmed = false

    // Called from GuiRendererMixin (Java); must stay public despite being internal use.
    fun composite(guiRenderState: GuiRenderState) {
        if (!SkijaNatives.ready) {
            // Drop anything HUD hooks queued before the native finished provisioning.
            Skija.discard()
            return
        }
        // No supported backend (a GPU API we don't adapt): disable, don't crash.
        val backend = SkijaBackend.active ?: run {
            SkijaOverlay.enabled = false
            Skija.discard()
            return
        }

        // One-time: realise the Skija GPU context on the first ready frame, so the user's first
        // overlay open doesn't eat the makeVulkan/makeGL cost as a hitch.
        if (!warmed) {
            warmed = true
            warmContext(backend)
        }

        // Fast path: overlay off and nothing queued -> no GL work. Once there IS content we must NOT
        // skip: content()/HUD borrow MC textures (real GL) that has to run inside saveState..restoreState;
        // running it before saveState poisons the snapshot and MC then renders the item atlas on the
        // wrong state (the GL dark-item bug).
        if (!SkijaOverlay.enabled && !Skija.hasContent()) {
            return
        }

        backend.saveState()
        try {
            // resetGLAll first so Skija re-reads MC's GL before any borrow during content().
            backend.onBeginFrame()

            // Item draws register into MC's render state, so this brackets the draw phase: the
            // optional content callback plus any HUD calls already queued for this frame.
            SkijaItems.beginFrame(guiRenderState)
            if (SkijaOverlay.enabled) {
                try {
                    SkijaOverlay.content()
                } catch (e: Throwable) {
                    LOG.error("Skija content draw failed", e)
                }
            }

            // Nothing queued this frame -> skip GPU work. The buffer keeps last frame's contents;
            // with nothing to blit we just don't blit, and a resuming HUD composites fresh next frame.
            if (!Skija.hasContent()) {
                return
            }

            val mc = Minecraft.getInstance()
            val window = mc.window
            val w = window.width
            val h = window.height
            if (window.isIconified || w <= 0 || h <= 0) {
                Skija.discard()
                return
            }

            ensureTargets(backend, w, h)
            // Shouldn't happen post-ensureTargets; drop the batch so it can't carry into the next frame.
            val surface = surface ?: run { Skija.discard(); return }
            val blitView = view ?: run { Skija.discard(); return }

            val canvas = surface.canvas
            canvas.clear(0)
            val guiScale = w.toFloat() / window.guiScaledWidth.coerceAtLeast(1)
            Skija.flush(canvas, Matrix3x2f().scale(guiScale))
            // Submit async (no CPU stall), then order this write before MC's blit read of the same
            // texture. Lets us blit the buffer drawn this frame with no submit semaphore (0.143.17 has none).
            backend.context.flushAndSubmit(surface, false)
            backend.orderWriteBeforeRead(blitView)

            val sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)
            // BlitRenderState float order is u0, u1, v0, v1. Flip per backend (GL FBO is bottom-left).
            val u0 = if (backend.flipBlitU) 1f else 0f
            val u1 = if (backend.flipBlitU) 0f else 1f
            val v0 = if (backend.flipBlitV) 1f else 0f
            val v1 = if (backend.flipBlitV) 0f else 1f
            guiRenderState.addBlitToCurrentLayer(
                BlitRenderState(
                    RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                    TextureSetup.singleTexture(blitView, sampler),
                    Matrix3x2f(),
                    0, 0, window.guiScaledWidth, window.guiScaledHeight,
                    u0, u1, v0, v1,
                    -1,
                    null,
                ),
            )
        } catch (t: Throwable) {
            LOG.error("BlackSkija compositing failed - disabling overlay", t)
            SkijaOverlay.enabled = false
            Skija.discard()
        } finally {
            backend.restoreState()
            SkijaItems.endFrame()
        }
    }

    // Forces the lazy DirectContext to be created now, off the first overlay-open frame, since
    // makeVulkan/makeGL is the bulk of that first-open freeze. Bracketed in saveState/restoreState so
    // GL context creation can't leave MC's state cache poisoned. On failure it's created lazily later.
    private fun warmContext(backend: SkijaBackend) {
        backend.saveState()
        try {
            val context = backend.context
            LOG.info("BlackSkija GPU context pre-warmed: {}", context)
        } catch (t: Throwable) {
            LOG.warn("BlackSkija context warmup failed (will create lazily on first use)", t)
        } finally {
            backend.restoreState()
        }
    }

    // (Re)creates the UI texture, view and Surface only when missing or the window resized.
    private fun ensureTargets(backend: SkijaBackend, w: Int, h: Int) {
        if (surface != null && texWidth == w && texHeight == h) return
        closeAll()
        val device = RenderSystem.getDevice()
        val tex = device.createTexture("blackskija ui", UI_TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1)
        val v = device.createTextureView(tex)
        val surf = backend.wrapTarget(v)
        surf.canvas.clear(0)
        texture = tex
        view = v
        surface = surf
        texWidth = w
        texHeight = h
    }

    private fun closeAll() {
        surface?.close()
        view?.close()
        texture?.close()
        surface = null
        view = null
        texture = null
    }

    // Releases all GPU resources (buffer first, then the Skija context). Wired to client shutdown.
    internal fun shutdown() {
        try {
            WrappedTextureCache.clear() // close borrowed images before the context that owns them
            closeAll()
            texWidth = 0
            texHeight = 0
            SkijaBackend.active?.dispose()
        } catch (e: Throwable) {
            LOG.warn("Skija shutdown cleanup failed", e)
        }
    }
}
