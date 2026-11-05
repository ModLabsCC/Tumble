// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.physics.PhysicsBodyDefinition
import cc.modlabs.tumble.physics.PhysicsBodyId
import cc.modlabs.tumble.physics.PhysicsBodySnapshot
import cc.modlabs.tumble.physics.PhysicsImpactEvent
import cc.modlabs.tumble.physics.PhysicsSectionPosition
import cc.modlabs.tumble.physics.TerrainBlockGeometry
import java.util.Collections
import java.util.UUID

@JvmInline
value class PhysicsDimensionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Physics dimension ID must not be blank" }
    }
}

sealed interface PhysicsCommand {
    data class CreateDimension(val dimension: PhysicsDimensionId) : PhysicsCommand
    data class RemoveDimension(val dimension: PhysicsDimensionId) : PhysicsCommand
    data class SpawnBody(
        val propId: UUID,
        val dimension: PhysicsDimensionId,
        val definition: PhysicsBodyDefinition,
        val requestId: UUID = UUID.randomUUID()
    ) : PhysicsCommand
    data class DestroyBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class CreateWeld(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId
    ) : PhysicsCommand
    data class DestroyWelds(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class DestroyWeld(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId
    ) : PhysicsCommand
    data class CreateRope(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId,
        val length: Float
    ) : PhysicsCommand
    data class CreateAxis(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId
    ) : PhysicsCommand
    data class CreateNoCollide(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId
    ) : PhysicsCommand
    data class DestroyToolConstraints(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class ReplaceTerrainSection(
        val dimension: PhysicsDimensionId,
        val section: PhysicsSectionPosition,
        val geometry: List<TerrainBlockGeometry>
    ) : PhysicsCommand
    data class RemoveTerrainSection(
        val dimension: PhysicsDimensionId,
        val section: PhysicsSectionPosition
    ) : PhysicsCommand
    data class WakeBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class SetMotionType(
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val motionType: PhysicsMotionType
    ) : PhysicsCommand
    data class ApplyCentralImpulse(
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val impulse: PhysicsVector
    ) : PhysicsCommand
    data class SetLinearVelocity(
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val velocity: PhysicsVector
    ) : PhysicsCommand
    data class SetTransform(
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val transform: PhysicsTransform
    ) : PhysicsCommand
    data class AddLinearVelocity(
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val delta: PhysicsVector
    ) : PhysicsCommand
    data class SetSubmersion(
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val fraction: Float
    ) : PhysicsCommand
    data class CreateTemporaryKinematicBody(
        val dimension: PhysicsDimensionId,
        val definition: PhysicsBodyDefinition,
        val target: cc.modlabs.tumble.physics.PhysicsTransform,
        val travelSeconds: Float,
        val lifetimeSteps: Int
    ) : PhysicsCommand
    data class ConfigureWorlds(
        val gravityY: Double,
        val buoyancy: Float,
        val impactSpeedThreshold: Float
    ) : PhysicsCommand
    data object Stop : PhysicsCommand
}

sealed interface PhysicsAcknowledgement {
    val propId: UUID

    data class BodyCreated(
        override val propId: UUID,
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val requestId: UUID
    ) : PhysicsAcknowledgement

    data class BodyCreationFailed(override val propId: UUID, val message: String, val requestId: UUID) : PhysicsAcknowledgement
}

data class PhysicsImpactNotification(
    val dimension: PhysicsDimensionId,
    val event: PhysicsImpactEvent
)

class PhysicsSnapshotFrame private constructor(
    val physicsStep: Long,
    val bodies: Map<PhysicsBodyId, PhysicsBodySnapshot>
) {
    companion object {
        fun of(physicsStep: Long, bodies: Map<PhysicsBodyId, PhysicsBodySnapshot>) = PhysicsSnapshotFrame(
            physicsStep,
            Collections.unmodifiableMap(HashMap(bodies))
        )

        val EMPTY = of(0, emptyMap())
    }
}

enum class PhysicsServiceState { NEW, RUNNING, STOPPING, STOPPED, FAILED }

data class PhysicsServiceDebugState(
    val state: PhysicsServiceState,
    val activeDimensions: Int,
    val runtimeBodies: Int,
    val terrainSections: Int,
    val queuedCommands: Int,
    val threadAlive: Boolean,
    val averageWorkMillis: Double,
    val failure: String?
)
