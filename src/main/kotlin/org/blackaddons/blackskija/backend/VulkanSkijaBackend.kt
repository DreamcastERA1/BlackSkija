package org.blackaddons.blackskija.backend

import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.BackendTexture
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import io.github.humbleui.skija.VkImageInfo
import io.github.humbleui.skija.VulkanAlloc
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12

object VulkanSkijaBackend : SkijaBackend {

    private const val VK_IMAGE_TILING_OPTIMAL = 0
    private const val VK_IMAGE_LAYOUT_GENERAL = 1
    private const val VK_USAGE_TRANSFER_SRC = 0x1
    private const val VK_USAGE_SAMPLED = 0x4
    private const val VK_QUEUE_FAMILY_IGNORED = -1
    private const val VK_SHARING_MODE_EXCLUSIVE = 0

    private var cached: DirectContext? = null

    override val context: DirectContext
        get() = cached ?: createContext().also { cached = it }

    private fun createContext(): DirectContext {
        val device = unwrapVulkanDevice(RenderSystem.getDevice())
        val vkInstance = device.instance().vkInstance()
        val vkDevice = device.vkDevice()
        val vkPhysicalDevice = vkDevice.physicalDevice
        val queue = device.graphicsQueue()

        val getInstanceProcAddr = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr")
        val getDeviceProcAddr = VK10.vkGetInstanceProcAddr(vkInstance, "vkGetDeviceProcAddr")

        return DirectContext.makeVulkan(
            vkInstance.address(),
            vkPhysicalDevice.address(),
            vkDevice.address(),
            queue.vkQueue().address(),
            queue.queueFamilyIndex(),
            getInstanceProcAddr,
            getDeviceProcAddr,
            VK12.VK_API_VERSION_1_2,
        )
    }

    override fun wrapTarget(view: GpuTextureView): Surface {
        val texture = view.texture() as VulkanGpuTexture
        val format = texture.format
        val renderTarget = BackendRenderTarget.makeVulkan(
            view.getWidth(0),
            view.getHeight(0),
            texture.vkImage(),
            VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            VulkanConst.toVk(format),
            VulkanConst.textureUsageToVk(texture.usage(), format) or VK_USAGE_TRANSFER_SRC,
            1,
            1,
        )
        return Surface.wrapBackendRenderTarget(
            context, renderTarget, SurfaceOrigin.TOP_LEFT, ColorType.RGBA_8888, ColorSpace.getSRGB(),
        )
    }

    override fun wrapTexture(view: GpuTextureView, premultiplied: Boolean): Image? {
        val texture = view.texture() as? VulkanGpuTexture ?: return null
        val format = texture.format

        val fakeAlloc = VulkanAlloc(0L, 0L, 0L, 0)

        val info = VkImageInfo(
            texture.vkImage(),
            fakeAlloc,
            VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            VulkanConst.toVk(format),
            VulkanConst.textureUsageToVk(texture.usage(), format) or VK_USAGE_SAMPLED or VK_USAGE_TRANSFER_SRC,
            1,
            1,
            VK_QUEUE_FAMILY_IGNORED,
            false,
            VK_SHARING_MODE_EXCLUSIVE,
        )
        val backend = BackendTexture.makeVulkan(view.getWidth(0), view.getHeight(0), info)
        val alpha = if (premultiplied) ColorAlphaType.PREMUL else ColorAlphaType.UNPREMUL
        val image = Image.borrowTextureFrom(
            context, backend, SurfaceOrigin.TOP_LEFT,
            ColorType.RGBA_8888, alpha, ColorSpace.getSRGB(), null,
        )
        backend.close()
        return image
    }

    override fun dispose() {
        cached?.close()
        cached = null
    }

    private fun unwrapVulkanDevice(device: GpuDevice): VulkanDevice =
        device.backend as VulkanDevice
}
