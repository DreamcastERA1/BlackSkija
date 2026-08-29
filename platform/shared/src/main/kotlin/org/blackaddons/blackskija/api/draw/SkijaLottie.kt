package org.blackaddons.blackskija.api.draw

import io.github.humbleui.skija.skottie.Animation
import org.blackaddons.blackskija.api.DeferredFree
import org.blackaddons.blackskija.api.RenderThread
import org.blackaddons.blackskija.api.Skija

/**
 * Lottie animations — vector motion described as JSON, which is what an animated sticker usually is.
 *
 * Skia renders them itself, so this is a parse and a cache; drawing is [Skija.lottie]. Nothing here
 * rasterizes, so one file serves every size, and the parsed animation is what carries the playhead —
 * which is why two things drawing the same animation at different times want two of them, keyed
 * apart.
 */
object SkijaLottie {

    // Parsed animations, kept for the life of the process: the same handful play over and over, and
    // never evicting means no handle can be freed while a queued draw still points at it.
    private val animations = HashMap<String, Animation>()

    /** Parses Lottie JSON into a cached animation under [key]. Draw it with [Skija.lottie]. */
    fun animation(key: String, json: String): Animation {
        // Parsing builds the whole scene graph natively, right here — not on first draw.
        RenderThread.require("a Lottie animation was parsed")
        return animations.getOrPut(key) {
            Animation.makeFromString(json)
        }
    }

    /** Parses a classpath `.json` Lottie file into a cached animation. */
    fun resource(path: String): Animation = animations[path] ?: run {
        val json = SkijaLottie::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            ?.decodeToString()
            ?: error("BlackSkija: Lottie file not found on classpath: $path")
        animation(path, json)
    }

    /** Forgets a cached animation and frees it once the current frame has been drawn. */
    fun delete(key: String) {
        animations.remove(key)?.let { DeferredFree.later(it) }
    }
}
