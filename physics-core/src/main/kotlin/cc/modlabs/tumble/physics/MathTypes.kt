// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.math.sqrt

data class PhysicsVector(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Physics vector components must be finite" }
    }

    operator fun plus(other: PhysicsVector) = PhysicsVector(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: PhysicsVector) = PhysicsVector(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double): PhysicsVector {
        require(scale.isFinite()) { "Physics vector scale must be finite" }
        return PhysicsVector(x * scale, y * scale, z * scale)
    }

    fun squaredLength() = x * x + y * y + z * z
    fun length() = sqrt(squaredLength())

    companion object {
        val ZERO = PhysicsVector(0.0, 0.0, 0.0)
    }
}

data class PhysicsRotation(val x: Double, val y: Double, val z: Double, val w: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) {
            "Physics rotation components must be finite"
        }
        require(squaredLength() > 1e-12) { "Physics rotation must be normalizable" }
    }

    fun squaredLength() = x * x + y * y + z * z + w * w

    fun normalized(): PhysicsRotation {
        val inverseLength = 1.0 / sqrt(squaredLength())
        return PhysicsRotation(x * inverseLength, y * inverseLength, z * inverseLength, w * inverseLength)
    }

    companion object {
        val IDENTITY = PhysicsRotation(0.0, 0.0, 0.0, 1.0)
    }
}

data class PhysicsTransform @JvmOverloads constructor(
    val position: PhysicsVector,
    val rotation: PhysicsRotation = PhysicsRotation.IDENTITY
)

data class PhysicsAabb(val min: PhysicsVector, val max: PhysicsVector) {
    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) {
            "Physics AABB minimum must not exceed maximum"
        }
    }

    fun expanded(amount: Double): PhysicsAabb {
        require(amount.isFinite() && amount >= 0.0) { "AABB expansion must be finite and non-negative" }
        val expansion = PhysicsVector(amount, amount, amount)
        return PhysicsAabb(min - expansion, max + expansion)
    }

    fun overlaps(other: PhysicsAabb) =
        max.x >= other.min.x && min.x <= other.max.x &&
            max.y >= other.min.y && min.y <= other.max.y &&
            max.z >= other.min.z && min.z <= other.max.z
}
