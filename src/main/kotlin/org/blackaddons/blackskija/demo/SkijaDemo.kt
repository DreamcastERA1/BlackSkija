package org.blackaddons.blackskija.demo

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.blackaddons.blackskija.api.Gradient
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.SkijaImages
import org.blackaddons.blackskija.api.SkijaItems
import org.blackaddons.blackskija.api.SkijaText
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object SkijaDemo {

    private val BG_TOP = Color(30, 33, 43)
    private val BG_BOTTOM = Color(18, 19, 24)
    private val BORDER = Color(90, 130, 255, 200)
    private val ACCENT = Color(90, 130, 255)
    private val INK = Color(210, 215, 230)
    private val MUTED = Color(120, 128, 150)

    private val BLOCK_ATLAS = Identifier.withDefaultNamespace("textures/atlas/blocks.png")
    private val STONE_SPRITE = Identifier.withDefaultNamespace("block/stone")

    private val DEMO_ITEM by lazy { ItemStack(Items.DIAMOND_SWORD) }

    fun draw() {
        val window = Minecraft.getInstance().window
        val sw = window.guiScaledWidth
        val sh = window.guiScaledHeight
        val t = (System.nanoTime() / 1_000_000L) / 1000.0

        val pw = (sw * 0.7f).coerceIn(460f, 820f)
        val ph = (sh * 0.7f).coerceIn(320f, 560f)
        val px = (sw - pw) / 2f
        val py = (sh - ph) / 2f

        Skija.dropShadow(px, py, pw, ph, 26, 2, 16, Color(0, 0, 0, 150))
        Skija.gradientRect(px, py, pw, ph, BG_TOP, BG_BOTTOM, Gradient.TOP_BOTTOM, 14)
        Skija.hollowRect(px, py, pw, ph, 1.5, BORDER, 14)

        val titleH = 36f
        val footerH = 26f
        Skija.drawHalfRoundedRect(px, py, pw, titleH, Color(255, 255, 255, 14), 14, roundTop = true)
        SkijaText.drawGradient("BlackSkija", px + 16, py + 11, ACCENT, Color(150, 205, 255), 15f)
        SkijaText.draw("Skija · Vulkan", px + pw - 66, py + 14, MUTED, 9f, align = Skija.Align.RIGHT)
        Skija.circle(px + pw - 22, py + 18, 5, Color(235, 90, 110))
        Skija.circle(px + pw - 38, py + 18, 5, Color(240, 190, 90))
        Skija.circle(px + pw - 54, py + 18, 5, Color(120, 210, 150))

        SkijaImages.drawMcSprite(BLOCK_ATLAS, STONE_SPRITE, px + 14, py + ph - 22, 16, 16)
        SkijaText.draw(
            "rect · gradient · circle · line · rotate · scissor · alpha · text · item",
            px + pw / 2, py + ph - 17, MUTED, 9f, align = Skija.Align.CENTER, shadow = true,
        )

        val pad = 16f
        val bx = px + pad
        val by = py + titleH + pad
        val bw = pw - pad * 2
        val bh = ph - titleH - footerH - pad * 2
        val cols = 4
        val rows = 2
        val gap = 14f
        val cw = (bw - gap * (cols - 1)) / cols
        val ch = (bh - gap * (rows - 1)) / rows

        fun cell(c: Int, r: Int, label: String, body: (x: Float, y: Float, w: Float, h: Float) -> Unit) {
            val cx = bx + c * (cw + gap)
            val cy = by + r * (ch + gap)
            Skija.rect(cx, cy, cw, ch, Color(255, 255, 255, 10), 10)
            Skija.hollowRect(cx, cy, cw, ch, 1.0, Color(255, 255, 255, 22), 10)
            body(cx, cy, cw, ch)
            SkijaText.draw(label, cx + cw / 2, cy + ch - 13, INK, 8f, align = Skija.Align.CENTER)
        }

        cell(0, 0, "rect") { x, y, w, h ->
            Skija.rect(x + 14, y + 16, w - 28, 22, ACCENT)
            Skija.rect(x + 14, y + h - 38, w - 28, 22, Color(120, 210, 150), 11)
        }

        cell(1, 0, "hollowRect") { x, y, w, h ->
            Skija.hollowRect(x + 14, y + 14, w - 28, h - 28, 1.0, INK, 8)
            Skija.hollowRect(x + 24, y + 24, w - 48, h - 48, 3.0, ACCENT, 6)
        }

        cell(2, 0, "gradientRect") { x, y, w, h ->
            val gw = (w - 42) / 2
            Skija.gradientRect(x + 14, y + 16, gw, h - 32, Color(255, 120, 90), Color(150, 40, 120), Gradient.TOP_BOTTOM, 8)
            Skija.gradientRect(x + 14 + gw + 14, y + 16, gw, h - 32, Color(90, 200, 255), Color(40, 70, 180), Gradient.LEFT_RIGHT, 8)
        }

        cell(3, 0, "alpha") { x, y, w, h ->
            val cy = y + h / 2
            for (i in 0 until 5) {
                Skija.push()
                Skija.globalAlpha(1f - i * 0.17f)
                Skija.circle(x + 22 + i * ((w - 44) / 4f), cy, 12, Color(120, 210, 170))
                Skija.pop()
            }
        }

        cell(0, 1, "line") { x, y, w, h ->
            Skija.line(x + 14, y + h - 16, x + w - 14, y + 16, 2, Color(255, 170, 90))
            Skija.line(x + 14, y + 16, x + w - 14, y + h - 16, 2, Color(255, 90, 120))
            val ox = x + w / 2
            val oy = y + h - 16
            for (i in 0..4) {
                val a = PI + i * (PI / 8)
                Skija.line(ox, oy, ox + (cos(a) * (w / 2 - 16)).toFloat(), oy + (sin(a) * (h / 2)).toFloat(), 1.5, MUTED)
            }
        }

        cell(1, 1, "rotate") { x, y, w, h ->
            val cx = x + w / 2
            val cy = y + h / 2
            Skija.push()
            Skija.translate(cx, cy)
            Skija.rotate(t)
            Skija.rect(-18, -18, 36, 36, ACCENT, 6)
            Skija.pop()
            Skija.push()
            Skija.translate(cx, cy)
            Skija.rotate(-t * 0.6)
            Skija.hollowRect(-26, -26, 52, 52, 2.0, Color(120, 210, 150, 180), 8)
            Skija.pop()
        }

        cell(2, 1, "scissor") { x, y, w, h ->
            Skija.pushScissor(x + 14, y + 14, w - 28, h - 28)
            val bob = (sin(t * 2) * 14).toFloat()
            Skija.gradientRect(x + 6, y + 6, w - 12, h - 12, Color(60, 70, 110), Color(30, 35, 60), Gradient.TOP_BOTTOM, 0)
            Skija.circle(x + w / 2, y + h / 2 + bob, h * 0.55f, Color(255, 200, 120))
            Skija.popScissor()
        }

        cell(3, 1, "mc item") { x, y, w, h ->
            val side = minOf(w, h) - 20
            SkijaItems.draw(DEMO_ITEM, x + (w - side) / 2, y + (h - side) / 2 - 4, side, side)
        }
    }
}
