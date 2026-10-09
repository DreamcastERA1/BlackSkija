package org.blackaddons.blackskija.demo

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.api.Skija
import org.blackaddons.blackskija.api.draw.SkijaText
import org.blackaddons.blackskija.api.screen.SkijaScreen
import java.awt.Color

// The SkijaDemo showcase hosted as a real SkijaScreen: opens like a menu, closes on ESC.
class SkijaDemoScreen : SkijaScreen(Component.literal("BlackSkija Demo")) {

    private lateinit var chatButton: Button
    private var sentTicks = 0

    override fun init() {
        super.init()
        val buttonY = ((height - SkijaDemo.panelHeight(height)) / 2f + 6f).toInt()
        chatButton = addWidget(object : Button(
            width / 2 - 52, buttonY, 104, 24, Component.literal("Send chat"),
            {
                minecraft.connection?.let { connection ->
                    connection.sendChat("Hello from the BlackSkija demo!")
                    sentTicks = 40
                }
            },
            { supplier -> supplier.get() },
        ) {
            // Minecraft handles input, focus, narration and click sounds; Skija draws the button.
            override fun extractContents(
                graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float,
            ) {}
        })
        chatButton.active = minecraft.connection != null
    }

    override fun tick() {
        super.tick()
        chatButton.active = minecraft.connection != null
        if (sentTicks > 0) sentTicks--
    }

    override fun draw() {
        SkijaDemo.draw()
        if (!::chatButton.isInitialized) return
        val window = minecraft.window
        val mouseX = minecraft.mouseHandler.xpos() * width / window.screenWidth
        val mouseY = minecraft.mouseHandler.ypos() * height / window.screenHeight
        val highlighted = chatButton.active && (chatButton.isFocused || chatButton.isMouseOver(mouseX, mouseY))
        val background = when {
            !chatButton.active -> Color(55, 58, 68)
            highlighted -> Color(105, 145, 255)
            else -> Color(65, 100, 200)
        }
        Skija.rect(chatButton.x, chatButton.y, chatButton.width, chatButton.height, background, 6)
        if (highlighted) {
            Skija.hollowRect(chatButton.x, chatButton.y, chatButton.width, chatButton.height, 1, Color.WHITE, 6)
        }
        SkijaText.draw(
            if (sentTicks > 0) "Sent!" else "Send chat",
            chatButton.x + chatButton.width / 2f, chatButton.y + 7f,
            if (chatButton.active) Color.WHITE else Color(140, 145, 155),
            10f, align = Skija.Align.CENTER,
        )
    }
}
