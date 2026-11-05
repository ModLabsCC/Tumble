// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.physics.PhysicsRotation
import net.minecraft.world.level.CardinalLighting

object PhysicsPropLighting {
    fun rotate(base: CardinalLighting, rotation: PhysicsRotation): CardinalLighting {
        val q = rotation.normalized()
        fun shade(x: Double, y: Double, z: Double): Float {
            val rotated = rotateVector(q, x, y, z)
            return (
                rotated.x * rotated.x * (if (rotated.x < 0.0) base.west() else base.east()) +
                    rotated.y * rotated.y * (if (rotated.y < 0.0) base.down() else base.up()) +
                    rotated.z * rotated.z * (if (rotated.z < 0.0) base.north() else base.south())
                ).toFloat()
        }
        return CardinalLighting(
            shade(0.0, -1.0, 0.0),
            shade(0.0, 1.0, 0.0),
            shade(0.0, 0.0, -1.0),
            shade(0.0, 0.0, 1.0),
            shade(-1.0, 0.0, 0.0),
            shade(1.0, 0.0, 0.0)
        )
    }

    private fun rotateVector(q: PhysicsRotation, x: Double, y: Double, z: Double): Vector {
        val tx = 2.0 * (q.y * z - q.z * y)
        val ty = 2.0 * (q.z * x - q.x * z)
        val tz = 2.0 * (q.x * y - q.y * x)
        return Vector(
            x + q.w * tx + (q.y * tz - q.z * ty),
            y + q.w * ty + (q.z * tx - q.x * tz),
            z + q.w * tz + (q.x * ty - q.y * tx)
        )
    }

    private data class Vector(val x: Double, val y: Double, val z: Double)
}
