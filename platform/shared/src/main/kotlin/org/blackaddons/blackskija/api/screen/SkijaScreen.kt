package org.blackaddons.blackskija.api.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.api.Skija

/**
 * Base class for a Skija-drawn Minecraft [Screen]. Subclasses implement [draw] with the [Skija] API
 * (gui-scaled coords, a frame is already open); MC handles the screen lifecycle, ESC-to-close and
 * input, while the compositor renders [draw] as an overlay blit.
 *
 * While open, the screen routes [SkijaOverlay.content] to its own [draw] and forces compositing on;
 * on close it restores whatever was active before, so it coexists with a background overlay. The
 * world keeps rendering behind it ([isPauseScreen] = false).
 *
 * The vanilla backdrop is chosen by [backdrop], plus an inline dim gradient
 * ([backdropDimTop]/[backdropDimBottom]). MC skips the world for one frame when a Screen opens (a
 * MC-side quirk, not ours), which can read as a black flash under a bright backdrop. The dim draws
 * in MC's own render pass (0 latency, unlike our compositor), masking that flash. Default is
 * vanilla's exact in-game dim, so it reads as native.
 */
abstract class SkijaScreen(title: Component) : Screen(title) {

    enum class Backdrop {
        /** Fully transparent; draw your own backdrop in [draw]. */
        NONE,

        /** Frosted world blur (respects the user's blur setting), no dim gradient. No open flash. */
        BLUR,

        /** The exact vanilla screen background (blur + dim + menu texture). May flash for one frame on open. */
        VANILLA,
    }

    private var saved = false
    private var prevEnabled = false
    private var prevContent: () -> Unit = {}

    override fun init() {
        if (!saved) {
            prevEnabled = SkijaOverlay.enabled
            prevContent = SkijaOverlay.content
            saved = true
        }
        SkijaOverlay.content = ::draw
        SkijaOverlay.enabled = true
    }

    override fun removed() {
        SkijaOverlay.enabled = prevEnabled
        SkijaOverlay.content = prevContent
        saved = false
    }

    /** Draw the screen UI in gui-scaled coordinates via [Skija]. A frame is open. */
    abstract fun draw()

    /** Which vanilla backdrop to draw behind the overlay. Override to change; defaults to [Backdrop.BLUR]. */
    protected open val backdrop: Backdrop get() = Backdrop.BLUR

    /**
     * Top/bottom ARGB of the inline dim gradient over [backdrop] (except [Backdrop.VANILLA], which
     * dims itself), which masks MC's one-frame world-skip on open. Defaults are vanilla's exact
     * in-game dim. A dark color with real alpha is what masks; a translucent pure black doesn't.
     * Both `0` = no dim (bright, but the rare flash returns).
     */
    protected open val backdropDimTop: Int get() = 0xC0101010.toInt()
    protected open val backdropDimBottom: Int get() = 0xD0101010.toInt()

    final override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        when (backdrop) {
            Backdrop.NONE -> {}
            Backdrop.BLUR -> extractBlurredBackground(graphics)
            // Vanilla already draws its own dim; don't double it.
            Backdrop.VANILLA -> { super.extractBackground(graphics, mouseX, mouseY, partialTick); return }
        }
        val top = backdropDimTop
        val bottom = backdropDimBottom
        if (top != 0 || bottom != 0) graphics.fillGradient(0, 0, width, height, top, bottom)
    }

    override fun isPauseScreen(): Boolean = false
}
