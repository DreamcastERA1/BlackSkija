package org.blackaddons.blackskija.backend.gl

/**
 * 26.3 keeps a colour mask per attachment, which this does not restore yet, so
 * [GlStateGuard] keeps querying the driver here. See the 26.1.2 counterpart.
 */
internal object MinecraftGlState {
    const val available = false

    fun restore() {}
}
