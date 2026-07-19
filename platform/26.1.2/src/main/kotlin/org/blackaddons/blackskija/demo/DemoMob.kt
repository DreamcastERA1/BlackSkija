package org.blackaddons.blackskija.demo

import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level

internal object DemoMob {
    fun create(level: Level, reason: EntitySpawnReason): LivingEntity? =
        EntityType.PIG.create(level, reason)
}
