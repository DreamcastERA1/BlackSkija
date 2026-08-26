package org.blackaddons.blackskija.backend.vulkan

import org.blackaddons.blackskija.backend.common.GpuProfileSample
import org.blackaddons.blackskija.backend.common.GpuProfiler
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo
import org.lwjgl.vulkan.VkCommandBufferBeginInfo
import org.lwjgl.vulkan.VkCommandPoolCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkFenceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkQueryPoolCreateInfo
import org.lwjgl.vulkan.VkSubmitInfo
import org.lwjgl.vulkan.VkQueue

internal class VulkanGpuProfiler(
    private val device: VkDevice,
    private val physicalDevice: VkPhysicalDevice,
    private val queue: VkQueue,
    private val queueFamily: Int,
) : GpuProfiler {

    private class Slot(
        val index: Int,
        val start: VkCommandBuffer,
        val end: VkCommandBuffer,
        val fence: Long,
    ) {
        var name: String? = null
        var inFlight = false
    }

    private var commandPool = 0L
    private var queryPool = 0L
    private var slots = emptyArray<Slot>()
    private var timestampPeriod = 1f
    private var nextSlot = 0
    private var active: Slot? = null
    private var ready = false
    private var available = supportsTimestamps()

    override val supported: Boolean get() = available
    override val hasPending: Boolean get() = slots.any { it.inFlight }

    override fun begin(name: String): Boolean {
        if (!available || active != null) return false
        if (!ready && !initialize()) return false
        val slot = slots[nextSlot]
        nextSlot = (nextSlot + 1) % slots.size
        if (slot.inFlight && VK10.vkGetFenceStatus(device, slot.fence) != VK10.VK_SUCCESS) return false
        slot.inFlight = false
        slot.name = name

        return runCatching {
            MemoryStack.stackPush().use { stack ->
                VK10.vkResetCommandBuffer(slot.start, 0)
                val begin = commandBegin(stack)
                check(VK10.vkBeginCommandBuffer(slot.start, begin) == VK10.VK_SUCCESS)
                VK10.vkCmdResetQueryPool(slot.start, queryPool, slot.index * 2, 2)
                VK10.vkCmdWriteTimestamp(slot.start, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, slot.index * 2)
                check(VK10.vkEndCommandBuffer(slot.start) == VK10.VK_SUCCESS)
                submit(stack, slot.start, 0L)
            }
            active = slot
            true
        }.getOrElse {
            available = false
            false
        }
    }

    override fun end() {
        val slot = active ?: return
        active = null
        runCatching {
            MemoryStack.stackPush().use { stack ->
                VK10.vkResetCommandBuffer(slot.end, 0)
                check(VK10.vkBeginCommandBuffer(slot.end, commandBegin(stack)) == VK10.VK_SUCCESS)
                VK10.vkCmdWriteTimestamp(slot.end, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, slot.index * 2 + 1)
                check(VK10.vkEndCommandBuffer(slot.end) == VK10.VK_SUCCESS)
                VK10.vkResetFences(device, stack.longs(slot.fence))
                submit(stack, slot.end, slot.fence)
                slot.inFlight = true
            }
        }.onFailure {
            available = false
            slot.inFlight = false
        }
    }

    override fun poll(): List<GpuProfileSample> {
        if (!ready) return emptyList()
        val result = ArrayList<GpuProfileSample>()
        for (slot in slots) {
            if (!slot.inFlight || VK10.vkGetFenceStatus(device, slot.fence) != VK10.VK_SUCCESS) continue
            val name = slot.name ?: continue
            MemoryStack.stackPush().use { stack ->
                val ticks = stack.mallocLong(2)
                val status = VK10.vkGetQueryPoolResults(
                    device, queryPool, slot.index * 2, 2, ticks, java.lang.Long.BYTES.toLong(), VK10.VK_QUERY_RESULT_64_BIT,
                )
                if (status == VK10.VK_SUCCESS) {
                    result += GpuProfileSample(name, ((ticks[1] - ticks[0]) * timestampPeriod).toLong())
                }
            }
            slot.inFlight = false
            slot.name = null
        }
        return result
    }

    override fun dispose() {
        if (ready) {
            runCatching {
                VK10.vkDeviceWaitIdle(device)
                for (slot in slots) VK10.vkDestroyFence(device, slot.fence, null)
                VK10.vkDestroyQueryPool(device, queryPool, null)
                VK10.vkDestroyCommandPool(device, commandPool, null)
            }
        }
        ready = false
        commandPool = 0L
        queryPool = 0L
        slots = emptyArray()
        active = null
    }

    private fun initialize(): Boolean = runCatching {
        MemoryStack.stackPush().use { stack ->
            val properties = VkPhysicalDeviceProperties.calloc(stack)
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties)
            timestampPeriod = properties.limits().timestampPeriod()

            val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily)
            val pPool = stack.mallocLong(1)
            check(VK10.vkCreateCommandPool(device, poolInfo, null, pPool) == VK10.VK_SUCCESS)
            commandPool = pPool[0]

            val queryInfo = VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(SLOT_COUNT * 2)
            check(VK10.vkCreateQueryPool(device, queryInfo, null, pPool) == VK10.VK_SUCCESS)
            queryPool = pPool[0]

            val allocation = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(SLOT_COUNT * 2)
            val buffers = stack.mallocPointer(SLOT_COUNT * 2)
            check(VK10.vkAllocateCommandBuffers(device, allocation, buffers) == VK10.VK_SUCCESS)

            val fenceInfo = VkFenceCreateInfo.calloc(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT)
            slots = Array(SLOT_COUNT) { index ->
                check(VK10.vkCreateFence(device, fenceInfo, null, pPool) == VK10.VK_SUCCESS)
                Slot(
                    index,
                    VkCommandBuffer(buffers[index * 2], device),
                    VkCommandBuffer(buffers[index * 2 + 1], device),
                    pPool[0],
                )
            }
        }
        ready = true
        true
    }.getOrElse {
        available = false
        dispose()
        false
    }

    private fun supportsTimestamps(): Boolean = MemoryStack.stackPush().use { stack ->
        val properties = VkPhysicalDeviceProperties.calloc(stack)
        VK10.vkGetPhysicalDeviceProperties(physicalDevice, properties)
        properties.limits().timestampComputeAndGraphics()
    }

    private fun commandBegin(stack: MemoryStack): VkCommandBufferBeginInfo =
        VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)

    private fun submit(stack: MemoryStack, commandBuffer: VkCommandBuffer, fence: Long) {
        val submit = VkSubmitInfo.calloc(stack)
            .sType(VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(stack.pointers(commandBuffer))
        check(VK10.vkQueueSubmit(queue, submit, fence) == VK10.VK_SUCCESS)
    }

    private companion object {
        const val SLOT_COUNT = 16
    }
}
