package org.blackaddons.blackskija.api.draw

import org.blackaddons.blackskija.compat.GpuTextureView
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

    /**
     * How many atlas pixels an item gets per screen pixel it is drawn at. The atlas slot follows the
     * largest item on screen, in real device pixels, so this holds at any resolution or GUI Scale.
     *
     * 2 by default: Minecraft renders items without antialiasing, and a 2x slot shrunk back down is
     * what smooths a head's edges. Going higher is not sharper — the atlas texture has no mipmaps,
     * so shrinking by more than ~2.5x starts skipping pixels instead of averaging them.
     */
    @Volatile
    var supersample: Float = 2f

    // Largest item drawn this frame, in device pixels — after the GUI Scale and any transform the
    // caller applied. In canvas units a mod that scales its own panels never reached the slot size,
    // so a 4K screen stretched items out of a slot half their size.
    private var maxItemDevicePx = 0f

    // Frames in a row that asked for a smaller slot than the atlas was built with.
    private var shrinkingFrames = 0

    // A slot this big already holds a 256px item at the default supersample, and the atlas is one
    // texture: past this, a screen full of items runs out of room and Minecraft skips some.
    private const val MAX_SLOT_PX = 512

    // Growing is urgent, shrinking only saves memory — and a size that wobbles (a screen opening and
    // closing, a hover zoom) would otherwise rebuild the atlas every time it crossed a step.
    private const val SHRINK_AFTER_FRAMES = 120

    internal fun beginFrame(state: GuiRenderState) {
        renderState = state
        ourStates.clear()
        requestedThisFrame.clear()
        maxItemDevicePx = 0f
    }

    /**
     * The atlas slot size to render this frame's items at, given vanilla's own [vanillaSlot]
     * (16 x GUI Scale). Called from `GuiRendererMixin`; public only because Java has to reach it.
     *
     * Always a whole multiple of [vanillaSlot]: vanilla items come out of the same atlas and are
     * blitted with nearest, which is lossless only at a whole-number shrink.
     */
    fun slotTextureSize(vanillaSlot: Int): Int {
        val wanted = ceil(maxItemDevicePx * supersample / vanillaSlot).toInt()
        val ceiling = max(1, MAX_SLOT_PX / vanillaSlot)
        return vanillaSlot * wanted.coerceIn(1, ceiling)
    }

    /**
     * Whether an atlas built at [builtSlot] has to go for one at [wantedSlot]. Minecraft reuses an
     * atlas while it has room and ignores the size it is handed, so without this a new size would
     * only take effect by accident. Public for the same reason as [slotTextureSize].
     *
     * A shrink waits [SHRINK_AFTER_FRAMES] so a briefly smaller item doesn't thrash the atlas, but
     * only while the big slots still hold all [itemCount] items: Minecraft recomputes the atlas size
     * from the smaller slot, gets the size it already has, and skips items instead of growing it.
     */
    fun shouldRebuildAtlas(builtSlot: Int, wantedSlot: Int, builtCapacity: Int, itemCount: Int): Boolean {
        if (wantedSlot >= builtSlot) {
            shrinkingFrames = 0
            return wantedSlot > builtSlot
        }
        if (itemCount <= builtCapacity && ++shrinkingFrames < SHRINK_AFTER_FRAMES) return false
        shrinkingFrames = 0
        return true
    }

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
        maxItemDevicePx = max(maxItemDevicePx, max(w.toFloat(), h.toFloat()) * Skija.deviceScale(canvas))
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
