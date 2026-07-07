package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Image

// Reuses the Skija Image wrapping a MC GPU texture instead of re-wrapping every frame. borrowTextureFrom
// doesn't snapshot: the Image samples live, so a cached wrapper shows new pixels with no latency. Keyed
// by GpuTextureView identity; a resource reload closes the view, so the stale entry (isClosed) re-wraps.
// Render-thread only.
internal object WrappedTextureCache {

    // Well above the distinct textures drawn in one frame, so LRU never closes an Image still queued
    // for this frame's flush.
    private const val MAX = 128

    private class Key(val view: GpuTextureView, val premultiplied: Boolean) {
        override fun equals(other: Any?): Boolean =
            other is Key && other.view === view && other.premultiplied == premultiplied
        override fun hashCode(): Int = System.identityHashCode(view) * 31 + premultiplied.hashCode()
    }

    private val cache = object : LinkedHashMap<Key, Image>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Image>): Boolean {
            if (size > MAX) { runCatching { eldest.value.close() }; return true }
            return false
        }
    }

    // Image borrowing [view], from cache or freshly wrapped. Owned by the cache; never close it.
    fun get(view: GpuTextureView, premultiplied: Boolean): Image? {
        val key = Key(view, premultiplied)
        cache[key]?.let { cached ->
            if (!view.isClosed) return cached
            // View was released (resource reload); drop the stale borrow and re-wrap below.
            runCatching { cached.close() }
            cache.remove(key)
        }
        val image = SkijaBackend.active?.wrapTexture(view, premultiplied) ?: return null
        cache[key] = image
        return image
    }

    // Close before the Skija context is disposed.
    fun clear() {
        for (image in cache.values) runCatching { image.close() }
        cache.clear()
    }
}
