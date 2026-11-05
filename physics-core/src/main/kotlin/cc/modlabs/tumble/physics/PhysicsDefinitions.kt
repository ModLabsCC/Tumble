// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

@JvmInline
value class PhysicsBodyId(val value: Long) {
    init {
        require(value > 0) { "Physics body ID must be positive" }
    }
}

enum class PhysicsMotionType { STATIC, KINEMATIC, DYNAMIC }

data class PhysicsMaterial(
    val friction: Float = 0.6f,
    val restitution: Float = 0f,
    val rollingResistance: Float = 0f,
    val density: Float = 1f
) {
    init {
        require(friction.isFinite() && friction >= 0f) { "Friction must be finite and non-negative" }
        require(restitution.isFinite() && restitution in 0f..1f) { "Restitution must be between 0 and 1" }
        require(rollingResistance.isFinite() && rollingResistance >= 0f) {
            "Rolling resistance must be finite and non-negative"
        }
        require(density.isFinite() && density > 0f) { "Density must be finite and positive" }
    }

    companion object {
        val DEFAULT = PhysicsMaterial()
        val LOW_FRICTION = PhysicsMaterial(friction = 0.04f)
        val HIGH_BOUNCE = PhysicsMaterial(restitution = 0.8f)
    }
}

data class PhysicsBox(val min: PhysicsVector, val max: PhysicsVector) {
    init {
        require(max.x > min.x && max.y > min.y && max.z > min.z) {
            "Physics box dimensions must be positive"
        }
    }

    val center get() = (min + max) * 0.5
    val halfExtents get() = (max - min) * 0.5

    companion object {
        fun centered(width: Double, height: Double, depth: Double): PhysicsBox {
            require(width.isFinite() && height.isFinite() && depth.isFinite()) { "Box dimensions must be finite" }
            require(width > 0.0 && height > 0.0 && depth > 0.0) { "Box dimensions must be positive" }
            return PhysicsBox(
                PhysicsVector(-width / 2.0, -height / 2.0, -depth / 2.0),
                PhysicsVector(width / 2.0, height / 2.0, depth / 2.0)
            )
        }

        val UNIT = centered(1.0, 1.0, 1.0)
    }
}

sealed interface PhysicsShapeDefinition {
    data class Box(val box: PhysicsBox) : PhysicsShapeDefinition

    data class CompoundBoxes(val boxes: List<PhysicsBox>) : PhysicsShapeDefinition {
        init {
            require(boxes.isNotEmpty()) { "Compound box shape must contain at least one box" }
        }
    }
}

data class PhysicsBuoyancy(
    val enabled: Boolean = false,
    val forceScale: Float = 1.5f,
    val linearDampingPerStep: Float = 0.04f,
    val angularDampingPerStep: Float = 0.08f,
    val levelingPerStep: Float = 0f
) {
    init {
        require(forceScale.isFinite() && forceScale >= 0f) { "Buoyancy force scale must be non-negative" }
        require(linearDampingPerStep.isFinite() && linearDampingPerStep in 0f..1f) {
            "Linear water damping must be between 0 and 1"
        }
        require(angularDampingPerStep.isFinite() && angularDampingPerStep in 0f..1f) {
            "Angular water damping must be between 0 and 1"
        }
        require(levelingPerStep.isFinite() && levelingPerStep >= 0f) {
            "Water leveling must be finite and non-negative"
        }
    }
}

data class PhysicsBodyDefinition @JvmOverloads constructor(
    val transform: PhysicsTransform,
    val shape: PhysicsShapeDefinition = PhysicsShapeDefinition.Box(PhysicsBox.UNIT),
    val material: PhysicsMaterial = PhysicsMaterial.DEFAULT,
    val linearVelocity: PhysicsVector = PhysicsVector.ZERO,
    val angularVelocity: PhysicsVector = PhysicsVector.ZERO,
    val motionType: PhysicsMotionType = PhysicsMotionType.DYNAMIC,
    val buoyancy: PhysicsBuoyancy = PhysicsBuoyancy(),
    val emitImpactEvents: Boolean = false
)

data class PhysicsBodySnapshot(
    @get:JvmName("getBodyId") val bodyId: PhysicsBodyId,
    val transform: PhysicsTransform,
    val linearVelocity: PhysicsVector,
    val angularVelocity: PhysicsVector,
    val sleeping: Boolean,
    val mass: Float = 0f
) {
    init {
        require(mass.isFinite() && mass >= 0f) { "Body mass must be finite and non-negative" }
    }
}

data class PhysicsImpactEvent(
    @get:JvmName("getBodyA") val bodyA: PhysicsBodyId?,
    @get:JvmName("getBodyB") val bodyB: PhysicsBodyId?,
    val point: PhysicsVector,
    val normal: PhysicsVector,
    val approachSpeed: Float
)

data class PhysicsWorldConfig(
    val frequencyHz: Int = 60,
    val substeps: Int = 4,
    val gravity: PhysicsVector = PhysicsVector(0.0, -32.0, 0.0),
    val workerCount: Int = 1,
    val impactSpeedThreshold: Float = 18f
) {
    init {
        require(frequencyHz > 0) { "Simulation frequency must be positive" }
        require(substeps > 0) { "Simulation substep count must be positive" }
        require(workerCount > 0) { "Worker count must be positive" }
        require(impactSpeedThreshold.isFinite() && impactSpeedThreshold >= 0f) {
            "Impact speed threshold must be finite and non-negative"
        }
    }

    val stepSeconds: Float get() = 1f / frequencyHz
}
