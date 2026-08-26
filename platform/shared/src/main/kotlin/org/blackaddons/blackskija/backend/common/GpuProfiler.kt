package org.blackaddons.blackskija.backend.common

internal data class GpuProfileSample(val name: String, val nanos: Long)

internal interface GpuProfiler {
    val supported: Boolean
    val hasPending: Boolean

    fun begin(name: String): Boolean
    fun end()
    fun poll(): List<GpuProfileSample>
    fun dispose()
}

/** Optional backend capability used only by [org.blackaddons.blackskija.api.SkijaProfiler]. */
internal interface GpuProfileBackend {
    val gpuProfiler: GpuProfiler
}

internal object UnsupportedGpuProfiler : GpuProfiler {
    override val supported = false
    override val hasPending = false

    override fun begin(name: String) = false
    override fun end() = Unit
    override fun poll(): List<GpuProfileSample> = emptyList()
    override fun dispose() = Unit
}
