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

internal object VulkanSkijaBackend : SkijaBackend, GpuProfileBackend {

    private const val VK_IMAGE_TILING_OPTIMAL = 0
    private const val VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL = 2
    private const val VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL = 5
    private const val VK_USAGE_TRANSFER_SRC = 0x1
    private const val VK_USAGE_SAMPLED = 0x4
    private const val VK_QUEUE_FAMILY_IGNORED = -1
    private const val VK_SHARING_MODE_EXCLUSIVE = 0

    override val displayName = "Vulkan"

    private var cached: DirectContext? = null

    /* Acquires and releases images around Skia's submit; created with the shared device/queue. */
    private var barrier: VulkanFrameBarrier? = null
    private var profiler: VulkanGpuProfiler? = null

    /*
     * Images acquire() moved out of Minecraft's GENERAL, with the layout Skia was told they are in. Skia
     * keeps believing the layout an image was wrapped with, so buffers are wrapped as color attachments
     * and borrowed textures as sampled, and only ever leave that layout between acquire and release.
     */
    private val acquired = LinkedHashMap<Long, Int>()

    override val gpuProfiler: GpuProfiler get() = profiler ?: UnsupportedGpuProfiler

    override val context: DirectContext
        get() = cached ?: createContext().also { cached = it }

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
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
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
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
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

    override fun acquire(targets: List<GpuTextureView>, sampled: Collection<GpuTextureView>) {
        val barrier = barrier?.takeIf { it.ensureReady() } ?: return
        val fresh = LinkedHashMap<Long, Int>()
        for (view in targets) take(view, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, fresh)
        for (view in sampled) take(view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, fresh)
        if (fresh.isNotEmpty()) barrier.submit(null, fresh, release = false)
    }

    override fun release() {
        if (acquired.isEmpty()) return
        barrier?.submit(null, acquired, release = true)
        acquired.clear()
    }

    private fun take(view: GpuTextureView, layout: Int, fresh: MutableMap<Long, Int>) {
        if (view.isClosed) return
        val image = (view.texture() as? VulkanGpuTexture)?.vkImage() ?: return
        if (acquired.putIfAbsent(image, layout) == null) fresh[image] = layout
    }

    override fun dispose() {
        profiler?.dispose()
        profiler = null
        barrier?.dispose()
        barrier = null
        acquired.clear()
        cached?.close()
        cached = null
    }

    private fun unwrapVulkanDevice(device: GpuDevice): VulkanDevice =
        device.backend as VulkanDevice
}
