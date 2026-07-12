package org.blackaddons.blackskija.api.draw

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Canvas
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaTextures
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Draws a Minecraft entity (a player model, a mob) into the Skija UI layer, so it composites in
 * paint order with the rest of the panel instead of underneath it. Vanilla renders a GUI entity as
 * a picture-in-picture blit that lands *below* our Skija overlay, which then dims or hides it; here
 * we let MC render the entity to its off-screen PiP texture, grab that texture instead of letting it
 * blit, and paint it ourselves through Skija.
 *
 * Same one-frame pipeline as [SkijaItems]: frame 1 the caller's [GuiEntityRenderState] is registered
 * so MC renders it to a texture and [GuiEntityCaptureMixin] grabs it; frame 2 the grabbed texture is
 * drawn. The caller supplies a stable [key] (the render state itself is rebuilt each frame), so the
 * capture from one frame pairs with the draw on the next. A one-frame lag is invisible on a slowly
 * turning avatar.
 */
object SkijaEntity {

    private var renderState: GuiRenderState? = null

    // key -> the PiP texture MC last rendered this entity into. The view is reused across frames, so
    // we only hold the reference and guard against a closed one (a resize recreates it).
    private val captured = HashMap<Any, GpuTextureView>()

    // The render states we registered this frame, mapped to their key, so the capture mixin can tell
    // ours from every other GUI entity/book/banner and look the key back up. Identity, not equality.
    private val ourStates: MutableMap<PictureInPictureRenderState, Any> =
        Collections.synchronizedMap(IdentityHashMap())

    private val requestedThisFrame = HashSet<Any>()

    internal fun beginFrame(state: GuiRenderState) {
        renderState = state
        ourStates.clear()
        requestedThisFrame.clear()
    }

    internal fun endFrame() {
        renderState = null
        captured.keys.retainAll(requestedThisFrame)
    }

    /**
     * Grab the entity's rendered texture instead of blitting it. Called from [GuiEntityCaptureMixin];
     * returns true (and the caller cancels the vanilla blit) only for a state we registered.
     */
    fun capture(state: PictureInPictureRenderState, view: GpuTextureView): Boolean {
        val key = ourStates[state] ?: return false
        captured[key] = view
        return true
    }

    /**
     * Queue an entity draw at [x],[y],[w],[h]. [state] must carry its own PiP region (`x0..y1`) so MC
     * renders it at the right resolution; it's registered for capture this frame and the texture
     * grabbed last frame is painted now. Callable on the render thread; keeps its place among other
     * [Skija] calls.
     */
    fun draw(key: Any, state: GuiEntityRenderState, x: Number, y: Number, w: Number, h: Number) {
        Skija.enqueue { canvas -> drawQueued(canvas, key, state, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()) }
    }

    private fun drawQueued(canvas: Canvas, key: Any, state: GuiEntityRenderState, x: Float, y: Float, w: Float, h: Float) {
        val rs = renderState ?: return
        requestedThisFrame.add(key)

        val view = captured[key]
        if (view != null && !view.isClosed) {
            val img = SkijaTextures.wrap(view, premultiplied = true)
            if (img != null) {
                // The PiP texture is bottom-up (its blit samples v 1->0), so flip vertically about the
                // box's mid-line before drawing.
                val save = canvas.save()
                try {
                    canvas.translate(0f, y + y + h)
                    canvas.scale(1f, -1f)
                    Skija.drawImageDirect(canvas, img, 0f, 0f, img.width.toFloat(), img.height.toFloat(), x, y, w, h, 0f, null)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }

        // Register this frame's state so MC renders it to a texture and the mixin captures it. Its blit
        // is cancelled there, so it never paints under our overlay.
        ourStates[state] = key
        rs.addPicturesInPictureState(state)
    }
}
