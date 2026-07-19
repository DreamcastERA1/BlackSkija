package org.blackaddons.blackskija.api.draw

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Canvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaTextures
import org.joml.Matrix3x2f
import java.awt.Color
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

object SkijaItems {

    private class Slot(val view: GpuTextureView, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private var renderState: GuiRenderState? = null
    private val captured = HashMap<Any, Slot>()
    private val ourStates: MutableSet<GuiItemRenderState> =
        Collections.newSetFromMap(IdentityHashMap())
    private val requestedThisFrame = HashSet<Any>()

    @Volatile
    var supersample: Float = 1f

    // Largest item draw this frame (logical px, max of w/h); drives the adaptive atlas slot size.
    private var maxItemPx = 0f

    internal fun beginFrame(state: GuiRenderState) {
        renderState = state
        ourStates.clear()
        requestedThisFrame.clear()
        maxItemPx = 0f
    }

    fun atlasSlotScale(): Int = max(1, ceil(maxItemPx * supersample / 16f).toInt())

    internal fun endFrame() {
        renderState = null
        captured.keys.retainAll(requestedThisFrame)
    }

    // Called from GuiItemCaptureMixin (Java). Must stay public despite being internal use.
    fun capture(state: GuiItemRenderState, view: GpuTextureView, u0: Float, v0: Float, u1: Float, v1: Float): Boolean {
        if (state !in ourStates) return false
        val id = state.itemStackRenderState().modelIdentity
        captured[id] = Slot(view, u0, v0, u1, v1)
        return true
    }

    /**
     * Queues an item draw. Callable from anywhere on the render thread (HUD hooks included); the
     * body runs at composite time where MC's render state is live. Like all GUI items it's a
     * one-frame pipeline: frame 1 registers the item so the capture mixin grabs its texture, frame 2
     * draws it. Keeps its place in the draw order among other [Skija] calls in the same frame.
     */
    fun draw(stack: ItemStack, x: Number, y: Number, w: Number, h: Number, radius: Number = 0, tint: Color? = null) {
        Skija.enqueue { canvas -> drawQueued(canvas, stack, x, y, w, h, radius, tint) }
    }

    private fun drawQueued(
        canvas: Canvas, stack: ItemStack, x: Number, y: Number, w: Number, h: Number, radius: Number, tint: Color?,
    ) {
        val rs = renderState ?: return
        maxItemPx = max(maxItemPx, max(w.toFloat(), h.toFloat()))
        val mc = Minecraft.getInstance()

        val state = TrackingItemStackRenderState()
        mc.itemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0)
        val id = state.modelIdentity
        requestedThisFrame.add(id)

        val slot = captured[id]
        if (slot != null && !slot.view.isClosed) {
            val img = SkijaTextures.wrap(slot.view, premultiplied = true)
            if (img != null) {
                val tw = img.width.toFloat()
                val th = img.height.toFloat()
                val sx = minOf(slot.u0, slot.u1) * tw
                val sy = minOf(slot.v0, slot.v1) * th
                val sw = abs(slot.u1 - slot.u0) * tw
                val sh = abs(slot.v1 - slot.v0) * th
                val fx = x.toFloat(); val fy = y.toFloat(); val fw = w.toFloat(); val fh = h.toFloat()
                val flipX = slot.u1 < slot.u0
                val flipY = slot.v1 < slot.v0
                val saveCount = canvas.save()
                try {
                    if (flipX || flipY) {
                        canvas.translate(if (flipX) fx + fx + fw else 0f, if (flipY) fy + fy + fh else 0f)
                        canvas.scale(if (flipX) -1f else 1f, if (flipY) -1f else 1f)
                    }
                    Skija.drawImageDirect(canvas, img, sx, sy, sw, sh, fx, fy, fw, fh, radius.toFloat(), tint)
                } finally {
                    canvas.restoreToCount(saveCount)
                }
            }
        }

        val itemState = GuiItemRenderState(Matrix3x2f(), state, 0, 0, ScreenRectangle(0, 0, 16, 16))
        ourStates.add(itemState)
        rs.addItem(itemState)
    }
}
