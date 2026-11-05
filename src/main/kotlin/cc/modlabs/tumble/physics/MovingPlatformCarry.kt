// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

object MovingPlatformCarry {
    fun isSafeDelta(delta: PhysicsVector) =
        delta.squaredLength() > 1e-10 && delta.squaredLength() <= MAX_DELTA_SQUARED

    fun displacement(
        previousPlatform: PhysicsAabb,
        actor: PhysicsAabb,
        platformDelta: PhysicsVector,
        grace: Double = 0.35
    ): PhysicsVector? {
        if (!isSafeDelta(platformDelta)) return null
        val feet = actor.min.y
        if (feet < previousPlatform.max.y - FEET_EPSILON || feet > previousPlatform.max.y + grace) return null
        if (actor.max.x <= previousPlatform.min.x || actor.min.x >= previousPlatform.max.x ||
            actor.max.z <= previousPlatform.min.z || actor.min.z >= previousPlatform.max.z
        ) return null
        return platformDelta
    }

    private const val FEET_EPSILON = 0.05
    private const val MAX_DELTA_SQUARED = 9.0
}
