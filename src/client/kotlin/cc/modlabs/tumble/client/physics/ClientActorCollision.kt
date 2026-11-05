// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.client.network.ClientPhysicsNetworking
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.physics.PhysicsAabb
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsTransform
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.PropActorCollision
import cc.modlabs.tumble.physics.PropSweepShape
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object ClientActorCollision {
    private var pushTick = Int.MIN_VALUE
    private val pushedProps = mutableSetOf<java.util.UUID>()

    fun clipMovement(actor: LocalPlayer, movement: Vec3): Vec3 {
        if (!actor.isAlive || actor.isSpectator || movement.lengthSqr() <= 1e-12) return movement
        val level = actor.level() as? ClientLevel ?: return movement
        val actorBounds = PhysicsServices.actorCollisionBounds(actor)
        val search = actorBounds.expandTowards(movement).inflate(MAX_TRACKING_DRIFT)
        val candidates = level.getEntitiesOfClass(PhysicsPropEntity::class.java, search) { it.actorCollidable }
        if (candidates.isEmpty()) return movement
        val now = System.nanoTime()
        val shapes = candidates.flatMapIndexed { owner, prop ->
            val sampled = ClientPhysicsState.sample(prop.uuid, now)
            val transform = sampled?.let { PhysicsTransform(it.position, it.rotation) }
                ?: PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z), prop.bodyRotation)
            prop.localCollisionBoxes.map { PropSweepShape(owner, transform, it) }
        }
        val requested = PhysicsVector(movement.x, movement.y, movement.z)
        val clipped = PropActorCollision.clipMovement(
            actorBounds.toPhysicsAabb(),
            requested,
            shapes
        )
        if (actor.tickCount != pushTick) {
            pushTick = actor.tickCount
            pushedProps.clear()
        }
        val horizontal = PhysicsVector(movement.x, 0.0, movement.z)
        if (horizontal.squaredLength() > 1e-12) {
            clipped.contacts.asSequence().map { candidates[it.owner] }.distinctBy { it.uuid }
                .filter { pushedProps.add(it.uuid) }
                .forEach { ClientPhysicsNetworking.requestPush(it.uuid, horizontal) }
        }
        val displacement = clipped.displacement
        return Vec3(displacement.x, displacement.y, displacement.z)
    }

    private fun AABB.toPhysicsAabb() = PhysicsAabb(
        PhysicsVector(minX, minY, minZ),
        PhysicsVector(maxX, maxY, maxZ)
    )

    // ponytail: bounded drift search; sync client entity positions if tuned prop speeds exceed this envelope.
    private const val MAX_TRACKING_DRIFT = 4.0
}
