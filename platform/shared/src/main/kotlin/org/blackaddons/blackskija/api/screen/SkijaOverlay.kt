package org.blackaddons.blackskija.api.screen

import org.blackaddons.blackskija.api.screen.SkijaOverlay.content
import org.blackaddons.blackskija.api.screen.SkijaOverlay.enabled


/**
 * Runtime control for the managed Skija overlay, shared between the draw side and the compositor so
 * neither package depends on the other. [SkijaScreen] (or your own code) sets [enabled]/[content];
 * the compositor reads them each frame.
 *
 * HUD-style drawing (calling `Skija.*` from your own render hook) composites regardless of [enabled];
 * these are only the single "managed source" hooks. The compositor also clears [enabled] if the GPU
 * backend is unsupported or a frame fails, degrading instead of crashing.
 */
object SkijaOverlay {

    /** Whether [content] is invoked each frame and the managed overlay is active. */
    @Volatile
    var enabled = false

    /**
     * Optional per-frame UI callback in gui-scaled coordinates, invoked while [enabled]. Just issue
     * `Skija.*` draw calls. Sugar for a single managed source (e.g. [SkijaScreen]); HUD hooks can
     * ignore it and call `Skija.*` directly.
     */
    @Volatile
    var content: () -> Unit = {}
}
