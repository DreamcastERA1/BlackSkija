/**
 * The render-API classes shared code names, resolved for this Minecraft version. 26.3 moved them
 * from `com.mojang.blaze3d` to `com.mojang.renderpearl`; shared sources import these aliases so one
 * import compiles against every platform. The 26.1.2 sibling points the same names at the old package.
 */
package org.blackaddons.blackskija.compat

typealias FilterMode = com.mojang.renderpearl.api.textures.FilterMode
typealias GpuTexture = com.mojang.renderpearl.api.textures.GpuTexture
typealias GpuTextureView = com.mojang.renderpearl.api.textures.GpuTextureView
typealias GlTextureView = com.mojang.renderpearl.backend.opengl.GlTextureView
