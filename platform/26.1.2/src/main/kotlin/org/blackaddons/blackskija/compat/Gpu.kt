/**
 * The render-API classes shared code names, resolved for this Minecraft version. 26.3 moved them
 * from `com.mojang.blaze3d` to `com.mojang.renderpearl`; shared sources import these aliases so one
 * import compiles against every platform. The 26.3 sibling points the same names at the new package.
 */
package org.blackaddons.blackskija.compat

typealias FilterMode = com.mojang.blaze3d.textures.FilterMode
typealias GpuTexture = com.mojang.blaze3d.textures.GpuTexture
typealias GpuTextureView = com.mojang.blaze3d.textures.GpuTextureView
typealias GlTextureView = com.mojang.blaze3d.opengl.GlTextureView
