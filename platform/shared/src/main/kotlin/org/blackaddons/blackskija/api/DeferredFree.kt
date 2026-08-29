package org.blackaddons.blackskija.api

import org.blackaddons.blackskija.api.DeferredFree.release


/**
 * Native handles that are finished with, but not yet safe to free.
 *
 * Nothing here is freed when the caller lets go of it, because drawing is deferred: a draw records
 * the handle into the frame's batch and the GPU work happens later, at the flush. Freeing at the
 * moment of replacement — an animation stepping to its next frame, a vector a caller deleted —
 * would pull the memory out from under a record that still points at it. The damage accumulates
 * silently and surfaces far from its cause, which is the same trap the paragraph cache documents.
 *
 * So handles are parked here and closed by [release], once the frame that could have recorded them
 * has been flushed and submitted.
 */
internal object DeferredFree {

    private val pending = ArrayList<AutoCloseable>()

    fun later(handle: AutoCloseable) {
        pending += handle
    }

    /** Frees everything parked since the last call. Compositor-only: see the note above on timing. */
    fun release() {
        if (pending.isEmpty()) return
        for (handle in pending) runCatching { handle.close() }
        pending.clear()
    }
}
