package org.blackaddons.blackskija.backend.gl

import org.blackaddons.blackskija.backend.common.GpuProfileSample
import org.blackaddons.blackskija.backend.common.GpuProfiler
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL33C

internal class GlGpuProfiler : GpuProfiler {

    private data class Pending(val name: String, val query: Int)

    override val supported: Boolean = GL.getCapabilities().OpenGL33
    override val hasPending: Boolean get() = pending.isNotEmpty()

    private val available = ArrayDeque<Int>()
    private val pending = ArrayDeque<Pending>()
    private var created = 0
    private var active: Pending? = null

    override fun begin(name: String): Boolean {
        if (!supported || active != null) return false
        val query = available.removeFirstOrNull() ?: createQuery() ?: return false
        GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, query)
        active = Pending(name, query)
        return true
    }

    override fun end() {
        val active = active ?: return
        GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED)
        pending.addLast(active)
        this.active = null
    }

    override fun poll(): List<GpuProfileSample> {
        if (!supported) return emptyList()
        val result = ArrayList<GpuProfileSample>()
        while (pending.isNotEmpty()) {
            val sample = pending.first()
            if (GL33C.glGetQueryObjecti(sample.query, GL33C.GL_QUERY_RESULT_AVAILABLE) == 0) break
            pending.removeFirst()
            result += GpuProfileSample(sample.name, GL33C.glGetQueryObjecti64(sample.query, GL33C.GL_QUERY_RESULT))
            available.addLast(sample.query)
        }
        return result
    }

    override fun dispose() {
        active?.let { GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED) }
        active = null
        while (pending.isNotEmpty()) GL33C.glDeleteQueries(pending.removeFirst().query)
        while (available.isNotEmpty()) GL33C.glDeleteQueries(available.removeFirst())
        created = 0
    }

    private fun createQuery(): Int? {
        if (created == MAX_QUERIES) return null
        created++
        return GL33C.glGenQueries()
    }

    private companion object {
        const val MAX_QUERIES = 16
    }
}
