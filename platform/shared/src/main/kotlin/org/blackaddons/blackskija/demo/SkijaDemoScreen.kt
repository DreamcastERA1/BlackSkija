package org.blackaddons.blackskija.demo

import net.minecraft.network.chat.Component
import org.blackaddons.blackskija.api.screen.SkijaScreen

// The SkijaDemo showcase hosted as a real SkijaScreen: opens like a menu, closes on ESC.
class SkijaDemoScreen : SkijaScreen(Component.literal("BlackSkija Demo")) {

    override fun draw() {
        SkijaDemo.draw()
    }
}
