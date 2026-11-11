package org.blackaddons.blackskija.backend.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.slf4j.LoggerFactory

/*
 * Moves the images BlackSkija touches between Minecraft's GENERAL and the layouts Skia was told they are
 * in: before Skia's submit (acquire) and after it, ahead of MC's blit read (release), so the compositor
 * can blit the buffer it drew this frame (0 latency, no double buffer). Each is a standalone submission on
 * MC's shared graphics queue; same-queue order makes the dependency, with no semaphore (0.143.18 exposes
 * none). Fenced command-buffer ring so a reused slot never stalls.
 */
internal class VulkanFrameBarrier(
    private val device: VkDevice,
    private val queue: VkQueue,
    private val queueFamily: Int,
) {

    private var commandPool = 0L // VK_NULL_HANDLE
    private var commandBuffers = arrayOfNulls<VkCommandBuffer>(0)
    private var fences = LongArray(0)
    private var ready = false
    private var submits = 0

    fun ensureReady(): Boolean {
        if (!ready) {
            runCatching { init() }.onFailure {
                LOG.warn("BlackSkija: pipeline-barrier init failed, falling back to no ordering", it)
            }
        }
        return ready
    }

    /* Submits [prefix] if any, then one barrier moving each of [layouts] from GENERAL into its layout, or back on [release]. */
    fun submit(prefix: VkCommandBuffer?, layouts: Map<Long, Int>, release: Boolean) {
        if (!ensureReady()) return
        val slot = submits % RING_SIZE
        submits++
        val cmd = commandBuffers[slot] ?: return
        val fence = fences[slot]

        MemoryStack.stackPush().use { stack ->
            val pFence = stack.longs(fence)
            /* The slot's fence is several submits old and already signalled, so this doesn't stall. */
            VK10.vkWaitForFences(device, pFence, true, Long.MAX_VALUE)
            VK10.vkResetFences(device, pFence)
            VK10.vkResetCommandBuffer(cmd, 0)

            val begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            VK10.vkBeginCommandBuffer(cmd, begin)

            if (layouts.isNotEmpty()) {
                val barriers = VkImageMemoryBarrier.calloc(layouts.size, stack)
                var i = 0
                for ((image, layout) in layouts) {
                    val barrier = barriers.get(i++)
                    barrier.sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                        .dstAccessMask(VK10.VK_ACCESS_MEMORY_READ_BIT or VK10.VK_ACCESS_MEMORY_WRITE_BIT)
                        .oldLayout(if (release) layout else VK10.VK_IMAGE_LAYOUT_GENERAL)
                        .newLayout(if (release) VK10.VK_IMAGE_LAYOUT_GENERAL else layout)
                        .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                        .image(image)
                    barrier.subresourceRange()
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)
                }
                VK10.vkCmdPipelineBarrier(
                    cmd,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0, null, null, barriers,
                )
            }
            VK10.vkEndCommandBuffer(cmd)

            val submit = VkSubmitInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(if (prefix != null) stack.pointers(prefix, cmd) else stack.pointers(cmd))
            VK10.vkQueueSubmit(queue, submit, fence)
        }
    }

    private fun init() {
        MemoryStack.stackPush().use { stack ->
            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily)
            val pPool = stack.mallocLong(1)
            check(VK10.vkCreateCommandPool(device, poolInfo, null, pPool) == VK10.VK_SUCCESS) { "vkCreateCommandPool failed" }
            commandPool = pPool.get(0)

            val allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(RING_SIZE)
            val pBuffers = stack.mallocPointer(RING_SIZE)
            check(VK10.vkAllocateCommandBuffers(device, allocInfo, pBuffers) == VK10.VK_SUCCESS) { "vkAllocateCommandBuffers failed" }
            commandBuffers = Array(RING_SIZE) { VkCommandBuffer(pBuffers.get(it), device) }

            // Signalled so the very first wait (before any submit) returns immediately.
            val fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT)
            fences = LongArray(RING_SIZE)
            val pFence = stack.mallocLong(1)
            for (i in 0 until RING_SIZE) {
                check(VK10.vkCreateFence(device, fenceInfo, null, pFence) == VK10.VK_SUCCESS) { "vkCreateFence failed" }
                fences[i] = pFence.get(0)
            }
        }
        ready = true
    }

    fun dispose() {
        if (ready) {
            runCatching {
                VK10.vkDeviceWaitIdle(device) // don't destroy pool/fences while a barrier is in flight
                for (f in fences) VK10.vkDestroyFence(device, f, null)
                if (commandPool != 0L) VK10.vkDestroyCommandPool(device, commandPool, null)
            }.onFailure { LOG.warn("BlackSkija: barrier teardown failed", it) }
        }
        ready = false
        commandPool = 0L
        commandBuffers = arrayOfNulls(0)
        fences = LongArray(0)
        submits = 0
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("blackskija")

        /* A frame submits at least an acquire and a release, so 8 slots keep a fence ~4 frames old. */
        private const val RING_SIZE = 8
    }
}
