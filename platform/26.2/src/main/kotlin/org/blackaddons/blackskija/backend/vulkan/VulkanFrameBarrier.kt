package org.blackaddons.blackskija.backend.vulkan

import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.*
import org.slf4j.LoggerFactory

// Orders BlackSkija's offscreen write before MC's blit read, so the compositor can blit the buffer it
// drew this frame (0 latency, no double buffer). Submits a standalone pipeline barrier onto MC's shared
// graphics queue between Skija's submit and MC's; same-queue order makes it a write-before-read dep with
// no semaphore (0.143.17 exposes none). Fenced command-buffer ring so a reused slot never stalls.
internal class VulkanFrameBarrier(
    private val device: VkDevice,
    private val queue: VkQueue,
    private val queueFamily: Int,
) {

    private var commandPool = 0L // VK_NULL_HANDLE
    private var commandBuffers = arrayOfNulls<VkCommandBuffer>(0)
    private var fences = LongArray(0)
    private var ready = false
    private var frame = 0

    fun order(image: Long) {
        if (!ready) {
            runCatching { init() }.onFailure {
                LOG.warn("BlackSkija: pipeline-barrier init failed, falling back to no ordering", it)
            }
            if (!ready) return
        }

        val slot = frame % FRAMES_IN_FLIGHT
        frame++
        val cmd = commandBuffers[slot] ?: return
        val fence = fences[slot]

        MemoryStack.stackPush().use { stack ->
            val pFence = stack.longs(fence)
            // Fence is FRAMES_IN_FLIGHT frames old, already signalled, so this doesn't stall.
            VK10.vkWaitForFences(device, pFence, true, Long.MAX_VALUE)
            VK10.vkResetFences(device, pFence)
            VK10.vkResetCommandBuffer(cmd, 0)

            val begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
            VK10.vkBeginCommandBuffer(cmd, begin)

            val barrier = VkImageMemoryBarrier.calloc(1, stack)
            barrier.sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .srcAccessMask(VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT or VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image)
            barrier.subresourceRange()
                .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)

            VK10.vkCmdPipelineBarrier(
                cmd,
                VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT or VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0, null, null, barrier,
            )
            VK10.vkEndCommandBuffer(cmd)

            val submit = VkSubmitInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd))
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
                .commandBufferCount(FRAMES_IN_FLIGHT)
            val pBuffers = stack.mallocPointer(FRAMES_IN_FLIGHT)
            check(VK10.vkAllocateCommandBuffers(device, allocInfo, pBuffers) == VK10.VK_SUCCESS) { "vkAllocateCommandBuffers failed" }
            commandBuffers = Array(FRAMES_IN_FLIGHT) { VkCommandBuffer(pBuffers.get(it), device) }

            // Signalled so the very first wait (before any submit) returns immediately.
            val fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT)
            fences = LongArray(FRAMES_IN_FLIGHT)
            val pFence = stack.mallocLong(1)
            for (i in 0 until FRAMES_IN_FLIGHT) {
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
        frame = 0
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("blackskija")

        // Depth of the command-buffer ring. 2 is ample for a trivial barrier; the slot's fence is
        // then 2 frames old and long signalled, so recycling never stalls.
        private const val FRAMES_IN_FLIGHT = 2
    }
}
