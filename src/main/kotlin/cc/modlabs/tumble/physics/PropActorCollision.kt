// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.math.abs

data class PropActorContact(
    val translation: PhysicsVector,
    val normal: PhysicsVector,
    val penetration: Double
)

data class PropSweepShape(val owner: Int, val transform: PhysicsTransform, val localBox: PhysicsBox)
data class PropSweepContact(val owner: Int, val normal: PhysicsVector)
data class PropMovementClip(val displacement: PhysicsVector, val contacts: List<PropSweepContact>)

/** Exact separating-axis test between a unit prop OBB and an actor AABB. */
object PropActorCollision {
    fun clipMovement(
        actor: PhysicsAabb,
        movement: PhysicsVector,
        shapes: List<PropSweepShape>
    ): PropMovementClip {
        val horizontal = PhysicsVector(movement.x, 0.0, movement.z)
        if (abs(movement.y) <= AXIS_EPSILON || horizontal.squaredLength() <= AXIS_EPSILON) {
            return clipSweep(actor, movement, shapes)
        }
        val vertical = clipSweep(actor, PhysicsVector(0.0, movement.y, 0.0), shapes)
        val movedActor = PhysicsAabb(actor.min + vertical.displacement, actor.max + vertical.displacement)
        val sideways = clipSweep(movedActor, horizontal, shapes)
        return PropMovementClip(vertical.displacement + sideways.displacement, vertical.contacts + sideways.contacts)
    }

    private fun clipSweep(
        actor: PhysicsAabb,
        movement: PhysicsVector,
        shapes: List<PropSweepShape>
    ): PropMovementClip {
        var moved = PhysicsVector.ZERO
        var remaining = movement
        val contacts = mutableListOf<PropSweepContact>()
        repeat(MAX_SWEEP_PASSES) {
            if (remaining.squaredLength() <= AXIS_EPSILON) return PropMovementClip(moved, contacts)
            val movedActor = PhysicsAabb(actor.min + moved, actor.max + moved)
            val hit = shapes.asSequence().mapNotNull { sweep(movedActor, remaining, it) }
                .filter { remaining.dot(it.normal) < -CONTACT_EPSILON }
                .minByOrNull(SweepHit::time)
                ?: return PropMovementClip(moved + remaining, contacts)
            val safeTime = (hit.time - SWEEP_TIME_EPSILON).coerceAtLeast(0.0)
            val step = remaining * safeTime
            moved += step
            var leftover = remaining * (1.0 - safeTime)
            val intoSurface = leftover.dot(hit.normal)
            if (intoSurface < 0.0) leftover -= hit.normal * intoSurface
            contacts += PropSweepContact(hit.owner, hit.normal)
            if ((leftover - remaining).squaredLength() <= AXIS_EPSILON) {
                return PropMovementClip(moved, contacts)
            }
            remaining = leftover
        }
        return PropMovementClip(moved, contacts)
    }

    fun aabbContact(prop: PhysicsAabb, actor: PhysicsAabb): PropActorContact? {
        val propCenter = prop.center()
        val actorCenter = actor.center()
        val overlaps = listOf(
            prop.max.x - actor.min.x to PhysicsVector(1.0, 0.0, 0.0),
            actor.max.x - prop.min.x to PhysicsVector(-1.0, 0.0, 0.0),
            prop.max.y - actor.min.y to PhysicsVector(0.0, 1.0, 0.0),
            actor.max.y - prop.min.y to PhysicsVector(0.0, -1.0, 0.0),
            prop.max.z - actor.min.z to PhysicsVector(0.0, 0.0, 1.0),
            actor.max.z - prop.min.z to PhysicsVector(0.0, 0.0, -1.0)
        )
        if (overlaps.any { it.first <= CONTACT_EPSILON }) return null
        val delta = actorCenter - propCenter
        val allowed = overlaps.filter { (_, normal) -> delta.dot(normal) >= 0.0 }
        val (penetration, normal) = allowed.minBy { it.first }
        return PropActorContact(normal * (penetration + SEPARATION_EPSILON), normal, penetration)
    }

    fun contact(prop: PhysicsTransform, actor: PhysicsAabb): PropActorContact? {
        return contact(prop, PhysicsBox.UNIT, actor)
    }

    fun contact(prop: PhysicsTransform, localBox: PhysicsBox, actor: PhysicsAabb): PropActorContact? {
        val axes = propAxes(prop.rotation)
        val actorCenter = PhysicsVector(
            (actor.min.x + actor.max.x) * 0.5,
            (actor.min.y + actor.max.y) * 0.5,
            (actor.min.z + actor.max.z) * 0.5
        )
        val actorHalf = PhysicsVector(
            (actor.max.x - actor.min.x) * 0.5,
            (actor.max.y - actor.min.y) * 0.5,
            (actor.max.z - actor.min.z) * 0.5
        )
        val localCenter = localBox.center
        val localHalf = localBox.halfExtents
        val propCenter = prop.position + axes[0] * localCenter.x + axes[1] * localCenter.y + axes[2] * localCenter.z
        val delta = actorCenter - propCenter
        val candidates = buildList {
            addAll(axes)
            add(WORLD_X)
            add(WORLD_Y)
            add(WORLD_Z)
            axes.forEach { propAxis ->
                listOf(WORLD_X, WORLD_Y, WORLD_Z).forEach { worldAxis ->
                    val cross = propAxis.cross(worldAxis)
                    if (cross.squaredLength() > AXIS_EPSILON) add(cross.normalized())
                }
            }
        }

        var bestAxis = WORLD_Y
        var bestPenetration = Double.POSITIVE_INFINITY
        candidates.forEach { axis ->
            val propRadius = localHalf.x * abs(axis.dot(axes[0])) +
                localHalf.y * abs(axis.dot(axes[1])) + localHalf.z * abs(axis.dot(axes[2]))
            val actorRadius = actorHalf.x * abs(axis.x) + actorHalf.y * abs(axis.y) + actorHalf.z * abs(axis.z)
            val signedDistance = delta.dot(axis)
            val penetration = propRadius + actorRadius - abs(signedDistance)
            if (penetration <= CONTACT_EPSILON) return null
            if (penetration < bestPenetration) {
                bestPenetration = penetration
                bestAxis = axis * if (signedDistance < 0.0) -1.0 else 1.0
            }
        }

        val translation = bestAxis * (bestPenetration + SEPARATION_EPSILON)
        return PropActorContact(translation, bestAxis, bestPenetration)
    }

    private fun propAxes(rotation: PhysicsRotation): List<PhysicsVector> {
        val q = rotation.normalized()
        val xx = q.x * q.x
        val yy = q.y * q.y
        val zz = q.z * q.z
        val xy = q.x * q.y
        val xz = q.x * q.z
        val yz = q.y * q.z
        val wx = q.w * q.x
        val wy = q.w * q.y
        val wz = q.w * q.z
        return listOf(
            PhysicsVector(1.0 - 2.0 * (yy + zz), 2.0 * (xy + wz), 2.0 * (xz - wy)),
            PhysicsVector(2.0 * (xy - wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz + wx)),
            PhysicsVector(2.0 * (xz + wy), 2.0 * (yz - wx), 1.0 - 2.0 * (xx + yy))
        )
    }

    private fun sweep(actor: PhysicsAabb, movement: PhysicsVector, shape: PropSweepShape): SweepHit? {
        val axes = propAxes(shape.transform.rotation)
        val actorCenter = (actor.min + actor.max) * 0.5
        val actorHalf = (actor.max - actor.min) * 0.5
        val localCenter = shape.localBox.center
        val localHalf = shape.localBox.halfExtents
        val propCenter = shape.transform.position + axes[0] * localCenter.x +
            axes[1] * localCenter.y + axes[2] * localCenter.z
        val delta = actorCenter - propCenter
        val candidates = buildList {
            addAll(axes)
            add(WORLD_X)
            add(WORLD_Y)
            add(WORLD_Z)
            axes.forEach { propAxis ->
                listOf(WORLD_X, WORLD_Y, WORLD_Z).forEach { worldAxis ->
                    val cross = propAxis.cross(worldAxis)
                    if (cross.squaredLength() > AXIS_EPSILON) add(cross.normalized())
                }
            }
        }
        var entry = Double.NEGATIVE_INFINITY
        var exit = Double.POSITIVE_INFINITY
        var entryAxis = WORLD_Y
        var closestSeparation = Double.NEGATIVE_INFINITY
        var closestAxis = WORLD_Y
        var closestDistance = 0.0
        candidates.forEach { axis ->
            val radius = localHalf.x * abs(axis.dot(axes[0])) +
                localHalf.y * abs(axis.dot(axes[1])) + localHalf.z * abs(axis.dot(axes[2])) +
                actorHalf.x * abs(axis.x) + actorHalf.y * abs(axis.y) + actorHalf.z * abs(axis.z)
            val distance = delta.dot(axis)
            val separation = abs(distance) - radius
            if (separation > closestSeparation) {
                closestSeparation = separation
                closestAxis = axis
                closestDistance = distance
            }
            val speed = movement.dot(axis)
            if (abs(speed) <= AXIS_EPSILON) {
                if (abs(distance) >= radius - TOUCH_EPSILON) return null
                return@forEach
            }
            val first = (-radius - distance) / speed
            val second = (radius - distance) / speed
            val axisEntry = minOf(first, second)
            val axisExit = maxOf(first, second)
            if (axisEntry > entry) {
                entry = axisEntry
                entryAxis = axis
            }
            exit = minOf(exit, axisExit)
            if (entry > exit) return null
        }
        if (closestSeparation <= TOUCH_EPSILON) {
            val direction = when {
                closestDistance > CONTACT_EPSILON -> 1.0
                closestDistance < -CONTACT_EPSILON -> -1.0
                movement.dot(closestAxis) > 0.0 -> -1.0
                else -> 1.0
            }
            val normal = actorResponseNormal(closestAxis * direction)
            return if (movement.dot(normal) < -CONTACT_EPSILON) SweepHit(shape.owner, 0.0, normal) else null
        }
        if (exit < 0.0 || entry > 1.0) return null
        val time = entry.coerceIn(0.0, 1.0)
        val signedDistance = delta.dot(entryAxis) + movement.dot(entryAxis) * time
        val direction = when {
            signedDistance > CONTACT_EPSILON -> 1.0
            signedDistance < -CONTACT_EPSILON -> -1.0
            movement.dot(entryAxis) > 0.0 -> -1.0
            else -> 1.0
        }
        return SweepHit(shape.owner, time, actorResponseNormal(entryAxis * direction))
    }

    private fun actorResponseNormal(normal: PhysicsVector): PhysicsVector =
        if (abs(normal.y) < WALKABLE_NORMAL_Y) PhysicsVector(normal.x, 0.0, normal.z).normalized() else normal

    private fun PhysicsVector.dot(other: PhysicsVector) = x * other.x + y * other.y + z * other.z
    private fun PhysicsVector.cross(other: PhysicsVector) = PhysicsVector(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )
    private fun PhysicsVector.normalized() = this * (1.0 / length())
    private fun PhysicsAabb.center() = (min + max) * 0.5

    private const val AXIS_EPSILON = 1e-12
    private const val CONTACT_EPSILON = 1e-7
    private const val TOUCH_EPSILON = 1e-7
    private const val SEPARATION_EPSILON = 1e-4
    private const val SWEEP_TIME_EPSILON = 1e-6
    private const val MAX_SWEEP_PASSES = 4
    private const val WALKABLE_NORMAL_Y = 0.7
    private val WORLD_X = PhysicsVector(1.0, 0.0, 0.0)
    private val WORLD_Y = PhysicsVector(0.0, 1.0, 0.0)
    private val WORLD_Z = PhysicsVector(0.0, 0.0, 1.0)
    private data class SweepHit(val owner: Int, val time: Double, val normal: PhysicsVector)
}
