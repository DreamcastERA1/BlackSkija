package org.blackaddons.blackskija.api

import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.backend.SkijaCompositor

/**
 * Base class for a Skija-drawn Minecraft [Screen]. Subclasses implement [draw] with the
 * [Skija] API (gui-scaled coords, a frame is already open); MC handles the screen
 * lifecycle, ESC-to-close, and input, while [SkijaCompositor] renders [draw] as an
 * overlay blit.
 *
 * While open, the screen routes the compositor's `content` to its own [draw] and forces
 * compositing on; on close it restores whatever was active before (so it coexists with a
 * background overlay without clobbering its state). The world keeps rendering behind
 * ([isPauseScreen] = false); MC's own screen background supplies the dim.
 */
abstract class SkijaScreen(title: Component) : Screen(title) {

    private var saved = false
    private var prevEnabled = false
    private var prevContent: () -> Unit = {}

    override fun init() {
        if (!saved) {
            prevEnabled = SkijaCompositor.enabled
            prevContent = SkijaCompositor.content
            saved = true
        }
        SkijaCompositor.content = ::draw
        SkijaCompositor.enabled = true
    }

    override fun removed() {
        SkijaCompositor.enabled = prevEnabled
        SkijaCompositor.content = prevContent
        saved = false
    }

    /** Draw the screen UI in gui-scaled coordinates via [Skija]. A frame is open. */
    abstract fun draw()

    override fun isPauseScreen(): Boolean = false
}
