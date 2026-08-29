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

    /**
     * Frees everything parked since the last call. Compositor-only: see the note above on timing.
     *
     * Indexed rather than iterated, because closing a handle can park more of them: an animation
     * owns a codec, a bitmap and its current frame, and hands all three to [later] as it closes.
     * Iterating threw `ConcurrentModificationException` the moment one was dropped, which a still
     * picture does routinely — the probe that asks how many frames it has is an animation too.
     * Anything parked mid-drain is as safe to free as the rest: the frame that could have recorded
     * it has already been flushed, which is the whole precondition for calling this.
     */
    fun release() {
        if (pending.isEmpty()) return
        var i = 0
        while (i < pending.size) {
            runCatching { pending[i].close() }
            i++
        }
        pending.clear()
    }
}
