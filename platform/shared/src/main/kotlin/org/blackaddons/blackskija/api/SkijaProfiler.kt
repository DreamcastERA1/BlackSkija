package org.blackaddons.blackskija.api

import org.blackaddons.blackskija.backend.common.SkijaBackend
import org.blackaddons.blackskija.backend.common.GpuProfileBackend

/**
 * Opt-in timing for a range of queued Skija work.
 *
 * The drawing API deliberately owns no profiling state. The compositor consumes the ranges here
 * when it submits them, which is the only point where CPU and GPU timings are meaningful.
 */
object SkijaProfiler {

    data class Samples(
        val cpuNanos: List<Long>,
        val gpuNanos: List<Long>,
        val gpuSupported: Boolean,
    )

    internal class Range(val name: String, val start: Int, var end: Int = -1)

    private val openRanges = ArrayDeque<Range>()
    private val completedRanges = ArrayList<Range>()
    private val cpuSamples = HashMap<String, MutableList<Long>>()
    private val gpuSamples = HashMap<String, MutableList<Long>>()
    private var gpuSupported = false
    private var needsGpuPoll = false

    fun <T> profile(name: String, block: () -> T): T {
        RenderThread.require("a profile was queued")
        val range = Range(name, Skija.size())
        openRanges.addLast(range)
        return try {
            block()
        } finally {
            openRanges.removeLastOrNull()
            range.end = Skija.size()
            if (range.end > range.start) completedRanges += range
        }
    }

    fun takeSamples(name: String): Samples = Samples(
        cpuSamples.remove(name).orEmpty(),
        gpuSamples.remove(name).orEmpty(),
        gpuSupported,
    )

    internal fun ranges(from: Int, to: Int): List<Range> = completedRanges.filter {
        it.start >= from && it.end <= to
    }

    internal fun <T> measure(backend: SkijaBackend, name: String, block: () -> T): T {
        val profiler = (backend as? GpuProfileBackend)?.gpuProfiler
        val gpuProfile = profiler?.begin(name) == true
        needsGpuPoll = needsGpuPoll || gpuProfile
        val started = System.nanoTime()
        return try {
            block()
        } finally {
            cpuSamples.getOrPut(name) { ArrayList() } += System.nanoTime() - started
            if (gpuProfile) profiler.end()
        }
    }

    internal fun drainGpu(backend: SkijaBackend) {
        if (!needsGpuPoll) return
        val profiler = (backend as? GpuProfileBackend)?.gpuProfiler ?: run {
            needsGpuPoll = false
            return
        }
        if (!profiler.hasPending) {
            needsGpuPoll = false
            return
        }
        gpuSupported = gpuSupported || profiler.supported
        profiler.poll().forEach { gpuSamples.getOrPut(it.name) { ArrayList() } += it.nanos }
        needsGpuPoll = profiler.hasPending
    }

    internal fun discard() {
        openRanges.clear()
        completedRanges.clear()
    }
}
