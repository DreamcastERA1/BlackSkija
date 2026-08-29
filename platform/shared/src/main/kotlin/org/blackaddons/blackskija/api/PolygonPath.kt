package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Path

/**
 * A reusable native polygon path. Obtain one through [Skija.createPolygon] and release it through
 * [Skija.deletePolygon]. The native handle stays private so callers cannot free it before queued
 * drawing has finished.
 */
class PolygonPath internal constructor(internal val native: Path) {
    internal var deleted = false
}
