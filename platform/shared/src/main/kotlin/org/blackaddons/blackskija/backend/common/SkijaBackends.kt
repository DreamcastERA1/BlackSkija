package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.systems.RenderSystem

internal object SkijaBackends {

    val activeName: String get() = active?.displayName ?: "—"

    private var cached: SkijaBackend? = null

    val active: SkijaBackend?
        get() {
            cached?.let { return it }
            val resolved = SkijaBackendResolver.resolve(RenderSystem.getDevice())
            if (resolved != null) cached = resolved
            return resolved
        }
}
