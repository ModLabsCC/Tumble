// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.entity

import cc.modlabs.tumble.TumbleMod
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory

object TumbleEntities {
    private val key = ResourceKey.create(Registries.ENTITY_TYPE, TumbleMod.id("physics_prop"))

    val PHYSICS_PROP: EntityType<PhysicsPropEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        key,
        EntityType.Builder.of(::PhysicsPropEntity, MobCategory.MISC)
            .sized(1.74f, 1.74f)
            .clientTrackingRange(10)
            .updateInterval(1)
            .build(key)
    )

    fun register() = Unit
}
