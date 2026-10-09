package org.blackaddons.blackskija.demo

import net.minecraft.client.Minecraft
import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.draw.SkijaText
import java.awt.Color
import kotlin.math.sin

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
        val radius = 10f

        /*
         * Shadow behind the glass panel.
         */
        Skija.dropShadow(
            x,
            y,
            w,
            h,
            18,
            2,
            radius,
            Color(0, 0, 0, 130),
        )

        /*
         * Real backdrop blur.
         *
         * This samples Minecraft's already-rendered framebuffer,
         * blurs it, and clips it to this rounded rectangle.
         */
        Skija.backdropBlur(
            x = x,
            y = y,
            w = w,
            h = h,
            blur = 20f,
            radius = radius,
            0.3f,
        )



        /*
         * Subtle glass border.
         */
        Skija.hollowRect(
            x,
            y,
            w,
            h,
            0.5,
            Color(255, 255, 255, 50),
            radius,
        )

        SkijaText.drawGradient(
            "BlackSkija HUD",
            x + 12,
            y + 11,
            hue(t * 0.20),
            hue(t * 0.20 + 0.5),
            13f,
        )

        SkijaText.draw(
            "FPS ${mc.fps}",
            x + 12,
            y + 33,
            Color(210, 215, 230),
            9f,
        )

        SkijaText.draw(
            "Frame $frames",
            x + 96,
            y + 33,
            Color(140, 150, 172),
            9f,
        )

        mc.player?.let { player ->
            SkijaText.draw(
                "XYZ %.1f %.1f %.1f".format(
                    player.x,
                    player.y,
                    player.z,
                ),
                x + 12,
                y + 46,
                Color(150, 160, 182),
                9f,
            )
        }

        val pulse =
            ((sin(t * 3.0) + 1.0) / 2.0)
                .toFloat()

        Skija.push()

        Skija.globalAlpha(
            0.2f + 0.8f * pulse,
        )

        SkijaText.draw(
            "recolor + alpha animating",
            x + 12,
            y + 60,
            Color(120, 230, 170),
            9f,
        )

        Skija.pop()

        val barX = x + 12f
        val barY = y + 78f
        val barW = w - 24f
        val barH = 14f

        Skija.rect(
            barX,
            barY,
            barW,
            barH,
            Color(0, 0, 0, 90),
            7,
        )

        val sweep =
            ((sin(t * 1.5) + 1.0) / 2.0)
                .toFloat()
                .coerceIn(0.06f, 0.94f)

        Skija.gradientRect(
            barX,
            barY,
            barW,
            barH,
            listOf(
                Color(30, 44, 92),
                hue(t * 0.5),
                Color(30, 44, 92),
            ),
            Gradient.LEFT_RIGHT,
            7,
            floatArrayOf(
                0f,
                sweep,
                1f,
            ),
        )
    }

    private fun hue(tSeconds: Double): Color {
        val h =
            ((tSeconds % 1.0).toFloat() + 1f) % 1f

        return Color.getHSBColor(
            h,
            0.62f,
            1.0f,
        )
    }
}