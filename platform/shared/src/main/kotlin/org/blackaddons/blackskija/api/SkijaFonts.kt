package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Data
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.Typeface
import io.github.humbleui.skija.paragraph.FontCollection
import io.github.humbleui.skija.paragraph.TypefaceFontProvider

/** Synthetic style drawn onto a face that ships no bold or italic of its own. [letterSpacing] is in ems. */
data class FontStyling(val bold: Boolean = false, val italic: Boolean = false, val letterSpacing: Float = 0f)

object SkijaFonts {

    /** Family name of the bundled default face. */
    const val DEFAULT = "blackskija-default"

    private const val DEFAULT_TTF = "/assets/blackskija/font/jetbrainsmono-regular.ttf"

    private val provider = TypefaceFontProvider()

    private val typefaces = HashMap<String, Typeface>()

    private val stylings = HashMap<String, FontStyling>()

    // Bumped by every registration, so caches built on a face can tell it was replaced.
    internal var generation = 0
        private set

    /** The typeface registered under [family], or null if nothing was registered for it. */
    internal fun typeface(family: String): Typeface? {
        collection // force the default face to register
        return typefaces[family]
    }

    internal fun styling(family: String): FontStyling? = stylings[family]

    internal val collection: FontCollection by lazy {
        register(DEFAULT, DEFAULT_TTF)
        FontCollection().apply {
            setDefaultFontManager(FontMgr.getDefault())
            setAssetFontManager(provider)
            setEnableFallback(true)
        }
    }

    /** Registers a classpath TTF/OTF under [family] (e.g. `/assets/blackskija/font/x.ttf`). */
    fun register(family: String, resourcePath: String) {
        val bytes = SkijaFonts::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: error("BlackSkija: bundled font not found on classpath: $resourcePath")
        register(family, bytes)
    }

    /** Registers encoded TTF/OTF bytes under [family]. */
    fun register(family: String, bytes: ByteArray) {
        val typeface: Typeface = Data.makeFromBytes(bytes).use { data ->
            FontMgr.getDefault().makeFromData(data)
        } ?: error("BlackSkija: failed to decode font (corrupt or unsupported)")
        register(family, typeface)
    }

    /** Registers [base]'s face again under [family], drawn with [styling]. */
    fun registerStyled(family: String, base: String, styling: FontStyling) {
        val typeface = typeface(base) ?: error("BlackSkija: no font registered as $base")
        stylings[family] = styling
        register(family, typeface)
    }

    /** Registers an already-decoded [typeface] under [family]. */
    fun register(family: String, typeface: Typeface) {
        provider.registerTypeface(typeface, family)
        typefaces[family] = typeface
        generation++
    }
}
