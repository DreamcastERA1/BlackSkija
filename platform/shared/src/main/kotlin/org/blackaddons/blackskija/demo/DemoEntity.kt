package org.blackaddons.blackskija.demo

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.LivingEntity
import org.blackaddons.blackskija.api.draw.SkijaEntity
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Renders a live Minecraft mob into a demo cell through [SkijaEntity] — the proof that an entity
 * composites over the Skija panel instead of under MC's picture-in-picture blit. Spins slowly. Needs
 * a world (an entity to build), so it draws nothing on the main menu.
 */
object DemoEntity {

    // Built once from the client level, reused every frame; never added to the world.
    private var mob: LivingEntity? = null

    fun draw(x: Float, y: Float, w: Float, h: Float, time: Double) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val entity = mob
            ?: DemoMob.create(level, EntitySpawnReason.MOB_SUMMONED)?.also {
                // Never spawned, so it has no entity ID — but the render path reads getId() (a held-item
                // model seed) and throws without one. Give it a synthetic, read-only id.
                it.id = Int.MAX_VALUE
                mob = it
            }
            ?: return

        // Face the viewer, turning slowly on its own.
        val look = 180f + (time * 40.0).toFloat() % 360f
        entity.yBodyRot = look; entity.yHeadRot = look; entity.yRot = look; entity.xRot = 0f

        val rs = mc.entityRenderDispatcher.extractEntity(entity, 1.0f)
        rs.lightCoords = 0xF000F0 // full bright, like the vanilla inventory portrait
        rs.shadowRadius = 0f

        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = (x + w).toInt(); val y1 = (y + h).toInt()
        val height = entity.bbHeight.coerceAtLeast(0.5f)
        val pose = Quaternionf().rotateZ(Math.PI.toFloat())
        val translation = Vector3f(0f, height * 0.5f, 0f)
        val scale = (y1 - y0) * 0.6f / height
        val state = GuiEntityRenderState(
            rs, translation, pose, Quaternionf(), x0, y0, x1, y1, scale,
            ScreenRectangle(x0, y0, x1 - x0, y1 - y0),
        )
        SkijaEntity.draw(this, state, x, y, w, h)
    }
}
