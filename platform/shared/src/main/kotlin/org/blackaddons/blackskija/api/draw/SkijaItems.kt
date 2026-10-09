package org.blackaddons.blackskija.api.draw

import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.systems.RenderSystem
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

    private class Slot(val view: GpuTextureView, val u0: Float, val v0: Float, val u1: Float, val v1: Float, val owned: GpuTexture? = null) {
        fun close() { if (owned != null) { view.close(); owned.close() } }
    }
    // Model identity can change as an animated model advances. The capture belongs to the
    // requested item, not that frame's model; include count for count-dependent GUI models.
    private class ItemKey(stack: ItemStack) {
        val stack = stack.copy()
        override fun hashCode() = ItemStack.hashItemAndComponents(stack) * 31 + stack.count
        override fun equals(other: Any?) = other is ItemKey && ItemStack.matches(stack, other.stack)
    }

    private var renderState: GuiRenderState? = null
    private val captured = HashMap<ItemKey, Slot>()
    private val ourStates = IdentityHashMap<GuiItemRenderState, ItemKey>()
    private val requestedThisFrame = HashSet<ItemKey>()

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

    internal fun clear() {
        captured.values.forEach { it.close() }
        captured.clear(); ourStates.clear(); requestedThisFrame.clear(); renderState = null
    }

    fun atlasSlotScale(): Int = max(1, ceil(maxItemPx * supersample / 16f).toInt())

    internal fun endFrame() {
        renderState = null
        val iterator = captured.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in requestedThisFrame) { entry.value.close(); iterator.remove() }
        }
    }

    // Called from GuiItemCaptureMixin (Java). Must stay public despite being internal use.
    fun capture(state: GuiItemRenderState, view: GpuTextureView, u0: Float, v0: Float, u1: Float, v1: Float): Boolean {
        val key = ourStates[state] ?: return false
        if (!state.itemStackRenderState().isAnimated) {
            captured.put(key, Slot(view, u0, v0, u1, v1))?.close()
            return true
        }
        // Animated atlas slots are discarded at MC endFrame and may be cleared/reused
        // before next frame's Skija replay. Keep only this tiny icon region, on the GPU.
        val source = view.texture()
        val mip = view.baseMipLevel()
        val tw = view.getWidth(0); val th = view.getHeight(0)
        val left = kotlin.math.round(minOf(u0, u1) * tw).toInt()
        val top = kotlin.math.round(minOf(v0, v1) * th).toInt()
        val width = kotlin.math.round(abs(u1-u0) * tw).toInt().coerceAtLeast(1)
        val height = kotlin.math.round(abs(v1-v0) * th).toInt().coerceAtLeast(1)
        val device = RenderSystem.getDevice()
        val previous = captured[key]
        val slot = if (previous?.owned != null && !previous.view.isClosed &&
            previous.view.getWidth(0) == width && previous.view.getHeight(0) == height && previous.owned.format == source.format) previous
        else {
            previous?.close()
            val texture = device.createTexture("BlackSkija animated item", GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
                source.format, width, height, 1, 1)
            Slot(device.createTextureView(texture), if(u0<u1) 0f else 1f, if(v0<v1) 0f else 1f,
                if(u0<u1) 1f else 0f, if(v0<v1) 1f else 0f, texture)
        }
        device.createCommandEncoder().copyTextureToTexture(source, slot.owned!!, mip, 0, 0, left, top, width, height)
        captured[key] = slot
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

    // A count-dependent model still gets its own exact capture, but a count update must
    // not erase the icon during the pipeline's one-frame warmup. Never borrow across
    // component changes (custom textures, enchants, names, etc.) or from closed views.
    private fun findCapture(stack: ItemStack, key: ItemKey): Slot? {
        captured[key]?.takeUnless { it.view.isClosed }?.let { return it }
        return captured.entries.firstOrNull { (previous, slot) ->
            !slot.view.isClosed && ItemStack.isSameItemSameComponents(stack, previous.stack)
        }?.value
    }

    private fun drawQueued(
        canvas: Canvas, stack: ItemStack, x: Number, y: Number, w: Number, h: Number, radius: Number, tint: Color?,
    ) {
        val rs = renderState ?: return
        maxItemPx = max(maxItemPx, max(w.toFloat(), h.toFloat()))
        val mc = Minecraft.getInstance()

        val state = TrackingItemStackRenderState()
        mc.itemModelResolver.updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0)
        val id = ItemKey(stack)
        requestedThisFrame.add(id)

        val slot = findCapture(stack, id)
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
        ourStates[itemState] = id
        rs.addItem(itemState)
    }
}
