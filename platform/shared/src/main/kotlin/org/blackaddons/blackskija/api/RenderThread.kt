package org.blackaddons.blackskija.api

import com.mojang.blaze3d.systems.RenderSystem

/**
 * Guards the render-thread-only contract the drawing API documents but could not previously enforce.
 *
 * Nothing here is synchronized: the frame batch, the paragraph cache and the native handles behind
 * them are all single-threaded by design, and making them thread-safe would cost every draw a lock
 * to serve a call that is a mistake either way — a draw queued from a worker lands in whatever frame
 * happens to be recording, which is not the one the caller meant.
 *
 * So the contract is enforced instead of defended. Without this the failure is silent and delayed:
 * a corrupted batch or a paragraph freed under a pending display list surfaces later as wrong glyphs
 * or a JVM crash inside Skia, nowhere near the call that caused it.
 */
internal object RenderThread {

    /** Throws unless the caller is on the render thread, naming [what] and the offending thread. */
    fun require(what: String) {
        if (RenderSystem.isOnRenderThread()) return
        throw IllegalStateException(
            "BlackSkija: $what from thread '${Thread.currentThread().name}', but the drawing API is " +
                "render-thread only. Hand the work to the client thread (Minecraft.getInstance().execute { }) " +
                "and draw from there.",
        )
    }
}
