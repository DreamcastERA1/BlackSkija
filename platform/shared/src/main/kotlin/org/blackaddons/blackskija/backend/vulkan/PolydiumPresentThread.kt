package org.blackaddons.blackskija.backend.vulkan

import net.fabricmc.loader.api.FabricLoader
import org.polyfrost.polydium.client.perf.PresentThread

/*
 * We wait for Polydium before rendering. Fahhhhh
 */
internal object PolydiumPresentThread {
    private var loaded = FabricLoader.getInstance().isModLoaded("polydium")

    fun drain() {
        if (!loaded) return
        try {
            PresentThread.drain()
        } catch (_: LinkageError) {
            loaded = false
        }
    }
}
