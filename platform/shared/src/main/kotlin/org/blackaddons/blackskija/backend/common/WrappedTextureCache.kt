package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.Image

internal object WrappedTextureCache {

    private const val MAX = 128

    private class Key(
        val view: GpuTextureView,
        val alphaType: ColorAlphaType,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Key &&
                    other.view === view &&
                    other.alphaType == alphaType

        override fun hashCode(): Int =
            System.identityHashCode(view) * 31 + alphaType.hashCode()
    }

    private val cache = object : LinkedHashMap<Key, Image>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, Image>,
        ): Boolean {
            if (size > MAX) {
                runCatching { eldest.value.close() }
                return true
            }

            return false
        }
    }

    fun get(
        view: GpuTextureView,
        alphaType: ColorAlphaType,
    ): Image? {
        val key = Key(
            view,
            alphaType,
        )

        cache[key]?.let { cached ->
            if (!view.isClosed) {
                return cached
            }

            runCatching { cached.close() }
            cache.remove(key)
        }

        val image = SkijaBackends.active?.wrapTexture(
            view,
            alphaType,
        ) ?: return null

        cache[key] = image

        return image
    }

    fun clear() {
        for (image in cache.values) {
            runCatching { image.close() }
        }

        cache.clear()
    }
}