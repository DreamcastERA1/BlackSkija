package org.blackaddons.blackskija.api

import io.github.humbleui.skija.Data
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.Typeface
import io.github.humbleui.skija.paragraph.FontCollection
import io.github.humbleui.skija.paragraph.TypefaceFontProvider

object SkijaFonts {

    /** Family name of the bundled default face. */
    const val DEFAULT = "blackskija-default"

    private const val DEFAULT_TTF = "/assets/blackskija/font/jetbrainsmono-regular.ttf"

    private val provider = TypefaceFontProvider()

    private val typefaces = HashMap<String, Typeface>()

    /** The typeface registered under [family], or null if nothing was registered for it. */
    internal fun typeface(family: String): Typeface? {
        collection // force the default face to register
        return typefaces[family]
    }

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
        val typeface: Typeface = FontMgr.getDefault().makeFromData(Data.makeFromBytes(bytes))
            ?: error("BlackSkija: failed to decode font (corrupt or unsupported): $resourcePath")
        register(family, typeface)
    }

    /** Registers an already-decoded [typeface] under [family]. */
    fun register(family: String, typeface: Typeface) {
        provider.registerTypeface(typeface, family)
        typefaces[family] = typeface
    }
}
