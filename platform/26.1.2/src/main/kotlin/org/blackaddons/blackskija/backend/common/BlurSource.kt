package org.blackaddons.blackskija.backend.common

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import io.github.humbleui.skija.Image
import net.minecraft.client.Minecraft

object BlurSource {
    private var texture: GpuTexture? = null
    private var view: GpuTextureView? = null
    var image: Image? = null
        private set
    private var width = 0
    private var height = 0

    /** Copies Minecraft's main color target into our own private texture */
    internal fun capture(backend: SkijaBackend) {
        val main = Minecraft.getInstance().mainRenderTarget
        val color = main.colorTexture ?: return
        val width = color.getWidth(0)
        val height = color.getHeight(0)
        if (image == null || this.width != width || this.height != height) {
            close()
            val tex = UiTexture.create("blackskija blur src", 0xf, width, height)
            val v = RenderSystem.getDevice().createTextureView(tex)
            texture = tex
            view = v
            image = backend.wrapTexture(v, premultiplied = true)
            this.width = width
            this.height = height
        }
        val tex = texture ?: return
        view?.let { WrappedTextureCache.borrowed += it }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(color, tex, 0, 0, 0, 0, 0, width, height)
    }

    internal fun close() {
        image?.close(); image = null
        view?.close(); view = null
        texture?.close(); texture = null
        width = 0; height = 0
    }
}
