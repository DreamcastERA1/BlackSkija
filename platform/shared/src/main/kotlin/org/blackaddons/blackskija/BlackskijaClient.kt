package org.blackaddons.blackskija

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import org.blackaddons.blackskija.api.SkijaTextures
import org.blackaddons.blackskija.api.screen.SkijaOverlay
import org.blackaddons.blackskija.backend.common.SkijaBackendBridge
import org.blackaddons.blackskija.backend.common.SkijaCompositor
import org.blackaddons.blackskija.backend.common.WrappedTextureCache
import org.blackaddons.blackskija.backend.natives.SkijaNatives
import org.blackaddons.blackskija.demo.SkijaDemo
import org.blackaddons.blackskija.demo.SkijaDemoScreen
import org.blackaddons.blackskija.demo.SkijaHud
import org.lwjgl.glfw.GLFW

class BlackskijaClient : ClientModInitializer {

    override fun onInitializeClient() {
        SkijaNatives.ensure()

        // Registers the backend's texture-wrapping bridge into the api seam.
        SkijaTextures.backend = SkijaBackendBridge

        ClientLifecycleEvents.CLIENT_STOPPING.register { SkijaCompositor.shutdown() }

        // A resource-pack change or F3+T reload re-uploads MC textures, so drop the wrappers borrowing
        // the old ones (they also self-heal via isClosed; this frees them promptly).
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath("blackskija", "texture_cache"),
            ResourceManagerReloadListener { WrappedTextureCache.clear() },
        )

        // The dev showcase (demo screen + always-on demo HUD + F6/F8/F9 keybinds) must NOT
        // activate when BlackSkija is used as a dependency: a consumer mod's own dev run
        // also reports isDevelopmentEnvironment, so that check alone leaks the demo into it.
        // Gate on a marker system property that ONLY BlackSkija's own run configs set (see
        // build.gradle.kts `runs`), so consumers never see the demo in dev or release.
        if (FabricLoader.getInstance().isDevelopmentEnvironment
            && System.getProperty("blackskija.demo").toBoolean()) {
            registerDevShowcase()
        }
    }

    private fun registerDevShowcase() {
        SkijaOverlay.content = SkijaDemo::draw

        // Always-on test HUD over gameplay: animated gradients + numbers to eyeball the recolor path
        // and watch for black-frame races.
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("blackskija", "test_hud"),
        ) { _, _ -> SkijaHud.draw() }

        val toggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blackskija.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, KeyMapping.Category.DEBUG),
        )
        val openScreenKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blackskija.open_screen", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, KeyMapping.Category.DEBUG),
        )
        val hudKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.blackskija.toggle_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, KeyMapping.Category.DEBUG),
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            while (toggleKey.consumeClick()) {
                SkijaOverlay.enabled = !SkijaOverlay.enabled
            }
            while (openScreenKey.consumeClick()) {
                Minecraft.getInstance().setScreenAndShow(SkijaDemoScreen())
            }
            while (hudKey.consumeClick()) {
                SkijaHud.enabled = !SkijaHud.enabled
            }
        }
    }
}
