package org.blackaddons.blackskija.backend.vulkan

import com.mojang.blaze3d.systems.GpuDevice
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import io.github.humbleui.skija.*
import org.blackaddons.blackskija.backend.common.GpuProfileBackend
import org.blackaddons.blackskija.backend.common.GpuProfiler
import org.blackaddons.blackskija.backend.common.SkijaBackend
import org.blackaddons.blackskija.backend.common.UnsupportedGpuProfiler
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import org.lwjgl.system.MemoryStack
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder

internal object VulkanSkijaBackend : SkijaBackend, GpuProfileBackend {

    private const val VK_IMAGE_TILING_OPTIMAL = 0
    private const val VK_IMAGE_LAYOUT_GENERAL = 1
    private const val VK_USAGE_TRANSFER_SRC = 0x1
    private const val VK_USAGE_SAMPLED = 0x4
    private const val VK_QUEUE_FAMILY_IGNORED = -1
    private const val VK_SHARING_MODE_EXCLUSIVE = 0

    override val displayName = "Vulkan"

    // Minecraft's framebuffer is vertically inverted relative to the borrowed top-left image.
    // Skija's own top-left UI target still uses the default, unflipped blit UVs.
    override val flipBackdropY = true

    private var cached: DirectContext? = null

    // Orders our write before MC's blit read (0-latency); created with the shared device/queue.
    private var barrier: VulkanFrameBarrier? = null
    private var profiler: VulkanGpuProfiler? = null

    override val gpuProfiler: GpuProfiler get() = profiler ?: UnsupportedGpuProfiler

    override val context: DirectContext
        get() = cached ?: createContext().also { cached = it }

    override fun onBeginFrame() {
        // MC records work until submit(); queue order cannot protect reads of unsubmitted work.
        // Append a barrier to its current batch and submit both before Skija uses the shared queue.
        val encoder = unwrapVulkanDevice(RenderSystem.getDevice()).createCommandEncoder()
        val commands = encoder.allocateAndBeginTransientCommandBuffer()
        MemoryStack.stackPush().use { stack ->
            VulkanCommandEncoder.memoryBarrier(commands, stack)
        }
        check(VK10.vkEndCommandBuffer(commands) == VK10.VK_SUCCESS) {
            "Failed to end Minecraft-to-Skija barrier"
        }
        encoder.execute(commands)
        encoder.submit()
    }

    private fun createContext(): DirectContext {
        val device = unwrapVulkanDevice(RenderSystem.getDevice())
        val vkInstance = device.instance().vkInstance()
        val vkDevice = device.vkDevice()
        val vkPhysicalDevice = vkDevice.physicalDevice
        val queue = device.graphicsQueue()

        barrier = VulkanFrameBarrier(vkDevice, queue.vkQueue(), queue.queueFamilyIndex())
        profiler = VulkanGpuProfiler(vkDevice, vkPhysicalDevice, queue.vkQueue(), queue.queueFamilyIndex())

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

    override fun wrapTexture(
        view: GpuTextureView,
        alphaType: ColorAlphaType,
    ): Image? {
        val texture =
            view.texture() as? VulkanGpuTexture
                ?: return null

        val format = texture.format

        val fakeAlloc = VulkanAlloc(
            0L,
            0L,
            0L,
            0,
        )

        val info = VkImageInfo(
            texture.vkImage(),
            fakeAlloc,
            VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            VulkanConst.toVk(format),
            VulkanConst.textureUsageToVk(
                texture.usage(),
                format,
            ) or VK_USAGE_SAMPLED or VK_USAGE_TRANSFER_SRC,
            1,
            1,
            VK_QUEUE_FAMILY_IGNORED,
            false,
            VK_SHARING_MODE_EXCLUSIVE,
        )

        val backend = BackendTexture.makeVulkan(
            view.getWidth(0),
            view.getHeight(0),
            info,
        )

        val image = Image.borrowTextureFrom(
            context,
            backend,
            SurfaceOrigin.TOP_LEFT,
            ColorType.RGBA_8888,
            alphaType,
            ColorSpace.getSRGB(),
            null,
        )

        backend.close()

        return image
    }

    override fun orderWriteBeforeRead(view: GpuTextureView) {
        val image = (view.texture() as? VulkanGpuTexture)?.vkImage() ?: return
        barrier?.order(image)
    }

    override fun dispose() {
        profiler?.dispose()
        profiler = null
        barrier?.dispose()
        barrier = null
        cached?.close()
        cached = null
    }

    private fun unwrapVulkanDevice(device: GpuDevice): VulkanDevice =
        device.backend as VulkanDevice
}
