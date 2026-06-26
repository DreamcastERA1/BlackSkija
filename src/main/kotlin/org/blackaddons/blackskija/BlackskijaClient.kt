package org.blackaddons.blackskija

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.blackaddons.blackskija.backend.SkijaCompositor
import org.blackaddons.blackskija.backend.SkijaNatives
import org.blackaddons.blackskija.demo.SkijaDemo
import org.blackaddons.blackskija.demo.SkijaDemoScreen
import org.lwjgl.glfw.GLFW

class BlackskijaClient : ClientModInitializer {

    override fun onInitializeClient() {
        SkijaNatives.ensure()

        ClientLifecycleEvents.CLIENT_STOPPING.register { SkijaCompositor.shutdown() }

        if (FabricLoader.getInstance().isDevelopmentEnvironment) {
            registerDevShowcase()
        }
    }

    private fun registerDevShowcase() {
        SkijaCompositor.content = SkijaDemo::draw

        val toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blackskija.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, KeyMapping.Category.DEBUG),
        )
        val openScreenKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blackskija.open_screen", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, KeyMapping.Category.DEBUG),
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            while (toggleKey.consumeClick()) {
                SkijaCompositor.enabled = !SkijaCompositor.enabled
            }
            while (openScreenKey.consumeClick()) {
                Minecraft.getInstance().setScreenAndShow(SkijaDemoScreen())
            }
        }
    }
}
