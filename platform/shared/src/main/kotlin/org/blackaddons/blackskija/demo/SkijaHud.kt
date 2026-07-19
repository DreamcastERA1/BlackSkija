package org.blackaddons.blackskija.demo

import net.minecraft.client.Minecraft
import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.draw.SkijaText
import java.awt.Color
import kotlin.math.sin

// Test HUD (dev showcase), drawn every frame from a Fabric HUD hook. Stresses the recolor
// path: a frozen title/bar means the recolor regressed, a black flicker means the blit races the draw.
object SkijaHud {

    @Volatile
    var enabled = true

    private var frames = 0L

    fun draw() {
        if (!enabled) return
        val mc = Minecraft.getInstance()
        frames++
        val t = (System.nanoTime() / 1_000_000L) / 1000.0

        val x = 8f
        val y = 8f
        val w = 214f
        val h = 104f

        Skija.dropShadow(x, y, w, h, 18, 2, 10, Color(0, 0, 0, 130))
        Skija.rect(x, y, w, h, Color(18, 20, 28, 210), 10)
        Skija.hollowRect(x, y, w, h, 1.0, Color(90, 130, 255, 120), 10)

        // Animated gradient title: colors rotate every frame (the recolor stress test).
        SkijaText.drawGradient("BlackSkija HUD", x + 12, y + 11, hue(t * 0.20), hue(t * 0.20 + 0.5), 13f)

        // Live numbers: the string changes each frame, testing the fresh-layout build path.
        SkijaText.draw("FPS ${mc.fps}", x + 12, y + 33, Color(210, 215, 230), 9f)
        SkijaText.draw("Frame $frames", x + 96, y + 33, Color(140, 150, 172), 9f)
        mc.player?.let { p ->
            SkijaText.draw("XYZ %.1f %.1f %.1f".format(p.x, p.y, p.z), x + 12, y + 46, Color(150, 160, 182), 9f)
        }

        // Pulsing alpha: proves the live alpha folds into the text color every frame.
        val pulse = ((sin(t * 3.0) + 1.0) / 2.0).toFloat()
        Skija.push()
        Skija.globalAlpha(0.2f + 0.8f * pulse)
        SkijaText.draw("recolor + alpha animating", x + 12, y + 60, Color(120, 230, 170), 9f)
        Skija.pop()

        // Gradient bar: a bright highlight sweeps across a dark track.
        val barX = x + 12f
        val barY = y + 78f
        val barW = w - 24f
        val barH = 14f
        Skija.rect(barX, barY, barW, barH, Color(0, 0, 0, 90), 7)
        val sweep = ((sin(t * 1.5) + 1.0) / 2.0).toFloat().coerceIn(0.06f, 0.94f)
        Skija.gradientRect(
            barX, barY, barW, barH,
            listOf(Color(30, 44, 92), hue(t * 0.5), Color(30, 44, 92)),
            Gradient.LEFT_RIGHT, 7, floatArrayOf(0f, sweep, 1f),
        )
    }

    // Color cycling through the hue wheel by tSeconds (1s = one full loop).
    private fun hue(tSeconds: Double): Color {
        val h = ((tSeconds % 1.0).toFloat() + 1f) % 1f
        return Color.getHSBColor(h, 0.62f, 1.0f)
    }
}
