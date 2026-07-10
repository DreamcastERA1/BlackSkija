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

    /**
     * One UI buffer: we draw into it and blit it in the same frame (0 latency). Ordering between our
     * write and MC's blit comes from SkijaBackend.orderWriteBeforeRead, so no double buffer is needed.
     */
    private class Target(val name: String) {
        var texture: GpuTexture? = null
        var view: GpuTextureView? = null
        var surface: Surface? = null
        var width = 0
        var height = 0

        fun matches(w: Int, h: Int) = surface != null && width == w && height == h

        /** (Re)creates the texture, view and Surface only when missing or the window resized. */
        fun ensure(backend: SkijaBackend, w: Int, h: Int) {
            if (matches(w, h)) return
            close()
            val device = RenderSystem.getDevice()
            val tex = device.createTexture(name, UI_TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1)
            val v = device.createTextureView(tex)
            val surf = backend.wrapTarget(v)
            surf.canvas.clear(0)
            texture = tex
            view = v
            surface = surf
            width = w
            height = h
        }

        fun close() {
            surface?.close()
            view?.close()
            texture?.close()
            surface = null
            view = null
            texture = null
            width = 0
            height = 0
        }
    }

    // Two buffers, because the frame is blitted at two different depths. Anything a HUD hook queued
    // belongs *under* screens and overlays; anything queued afterwards (a Skija screen, tooltips)
    // belongs on top. One buffer cannot be sampled at two depths without also showing the other's
    // content, so each blit gets its own.
    private val hudTarget = Target("blackskija hud")
    private val overlayTarget = Target("blackskija ui")

    // Ops queued when the HUD finished extracting — the split between the two buffers. -1 when the
    // boundary wasn't reached this frame (no HUD, or no buffer yet), which sends everything on top.
    private var hudSplit = -1

    private var warmed = false

    /**
     * Records where the HUD's draws end and reserves their place in the GUI's depth order, by
     * registering the blit into the layer that is current *right now* — while `Gui` has extracted
     * the HUD but not yet the overlay or the screen, both of which open a new stratum. The blit only
     * references the texture; it is filled later, in [composite]. Registering it there instead would
     * put it in the topmost layer, painting the HUD over the pause menu and the resource-pack overlay.
     *
     * Called from GuiExtractMixin (Java); must stay public despite being internal use.
     */
    fun markHudLayer(guiRenderState: GuiRenderState) {
        if (!SkijaNatives.ready) return
        val backend = SkijaBackend.active ?: return

        // Nothing queued by a HUD hook -> nothing to place under the screens.
        val queued = Skija.size()
        if (queued == 0) return

        val window = Minecraft.getInstance().window
        // The buffer is created in composite(), so on the first frame (and the frame a resize lands)
        // there is none to point at yet. Leave the split unset: this frame's HUD composites on top,
        // as it used to, and the next frame has its buffer.
        if (!hudTarget.matches(window.width, window.height)) return
        val blitView = hudTarget.view ?: return

        hudSplit = queued
        addBlit(guiRenderState, backend, blitView, window.guiScaledWidth, window.guiScaledHeight)
    }

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
            if (window.isIconified || w <= 0 || h <= 0) return

            hudTarget.ensure(backend, w, h)
            overlayTarget.ensure(backend, w, h)

            val guiScale = w.toFloat() / window.guiScaledWidth.coerceAtLeast(1)
            val pose = Matrix3x2f().scale(guiScale)

            // markHudLayer already registered this buffer's blit, down at the HUD's depth; all that
            // is left is to fill it with the ops queued up to the split.
            if (hudSplit > 0) render(backend, hudTarget, pose, 0, hudSplit)

            // Everything queued after the HUD finished — a Skija screen, tooltips — plus whatever
            // content() just added. It goes on top, so its blit is registered here, at the topmost layer.
            val overlayFrom = hudSplit.coerceAtLeast(0)
            if (Skija.size() > overlayFrom) {
                render(backend, overlayTarget, pose, overlayFrom, Int.MAX_VALUE)
                overlayTarget.view?.let { addBlit(guiRenderState, backend, it, window.guiScaledWidth, window.guiScaledHeight) }
            }
        } catch (t: Throwable) {
            LOG.error("BlackSkija compositing failed - disabling overlay", t)
            SkijaOverlay.enabled = false
        } finally {
            // One batch feeds both buffers, so it is cleared once, after the last range is replayed.
            Skija.discard()
            hudSplit = -1
            backend.restoreState()
            SkijaItems.endFrame()
        }
    }

    /** Replays `batch[from, to)` onto [target]'s surface and orders the write before MC reads it. */
    private fun render(backend: SkijaBackend, target: Target, pose: Matrix3x2f, from: Int, to: Int) {
        val surface = target.surface ?: return
        val view = target.view ?: return
        val canvas = surface.canvas
        canvas.clear(0)
        Skija.flush(canvas, pose, from, to)
        // Submit async (no CPU stall), then order this write before MC's blit read of the same
        // texture. Lets us blit the buffer drawn this frame with no submit semaphore (0.143.17 has none).
        backend.context.flushAndSubmit(surface, false)
        backend.orderWriteBeforeRead(view)
    }

    /** Adds a full-screen blit of [blitView] into the GUI render state's current layer. */
    private fun addBlit(
        guiRenderState: GuiRenderState, backend: SkijaBackend, blitView: GpuTextureView,
        guiScaledWidth: Int, guiScaledHeight: Int,
    ) {
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
                0, 0, guiScaledWidth, guiScaledHeight,
                u0, u1, v0, v1,
                -1,
                null,
            ),
        )
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

    // Releases all GPU resources (buffers first, then the Skija context). Wired to client shutdown.
    internal fun shutdown() {
        try {
            WrappedTextureCache.clear() // close borrowed images before the context that owns them
            hudTarget.close()
            overlayTarget.close()
            SkijaBackend.active?.dispose()
        } catch (e: Throwable) {
            LOG.warn("Skija shutdown cleanup failed", e)
        }
    }
}
