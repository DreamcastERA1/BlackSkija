package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.svg.SVGDOM
import io.github.humbleui.types.Rect
import org.blackaddons.blackskija.api.Skija

/**
 * A parsed SVG document, ready to be drawn at any size by [Skija.svg].
 *
 * It carries its own coordinate box alongside the document because asking the document for it is a
 * native call that builds a wrapper object, and the answer never changes — which would otherwise be
 * paid once per drawn frame.
 *
 * Obtain one from [SkijaVectors.svgResource] or [SkijaVectors.svg], which own it.
 */
class SkijaSvg internal constructor(internal val dom: SVGDOM) : AutoCloseable {

    /**
     * The document's own coordinate box — its `viewBox` — or null if it declares none.
     *
     * This is what makes a box mean a box. An SVG that states `width="24" height="24"` paints at 24
     * pixels no matter how large a container it is given, and most icon files state exactly that, so
     * a drawing surface that only set the container size would silently ignore the size it was
     * asked for. Knowing the document's own units lets the draw scale into the box instead.
     */
    internal val viewBox: Rect? = runCatching { dom.root?.viewBox }.getOrNull()
        ?.takeIf { it.width > 0f && it.height > 0f }

    override fun close() = dom.close()
}
