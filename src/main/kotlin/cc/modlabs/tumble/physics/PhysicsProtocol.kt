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

class PhysicsRestoreToken {
    @Volatile internal var valid = true
}

data class RestoreBody(val propId: UUID, val requestId: UUID, val definition: PhysicsBodyDefinition, val terrainGuard: AssemblyTerrainGuard? = null)
data class ParkBody(val propId: UUID, val bodyId: PhysicsBodyId)

sealed interface PhysicsCommand {
    data class CreateDimension(val dimension: PhysicsDimensionId) : PhysicsCommand
    data class RemoveDimension(val dimension: PhysicsDimensionId) : PhysicsCommand
    data class SpawnBody(
        val propId: UUID,
        val dimension: PhysicsDimensionId,
        val definition: PhysicsBodyDefinition,
        val requestId: UUID = UUID.randomUUID(),
        val terrainGuard: AssemblyTerrainGuard? = null
    ) : PhysicsCommand
    data class ParkComponent(val dimension: PhysicsDimensionId, val bodies: List<ParkBody>, val requestId: UUID) : PhysicsCommand
    data class RestoreComponent(
        val dimension: PhysicsDimensionId,
        val bodies: List<RestoreBody>,
        val joints: List<RestoredJoint>,
        val token: PhysicsRestoreToken? = null
    ) : PhysicsCommand
    data class ActivateRestoredComponent(val token: PhysicsRestoreToken) : PhysicsCommand
    data class PrepareConnection(val dimension: PhysicsDimensionId, val bodyA: PhysicsBodyId, val bodyB: PhysicsBodyId, val requestId: UUID) : PhysicsCommand
    data class InstallPreparedConnection(
        val dimension: PhysicsDimensionId, val bodyA: PhysicsBodyId, val bodyB: PhysicsBodyId,
        val requestId: UUID, val kind: PhysicsJointKind, val frames: PhysicsJointFrames, val ropeLength: Float
    ) : PhysicsCommand
    data class CancelConnectionPreparation(val dimension: PhysicsDimensionId, val requestId: UUID) : PhysicsCommand
    data class DestroyBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class CreateWeld(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId,
        val requestId: UUID? = null
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
        val length: Float,
        val requestId: UUID? = null
    ) : PhysicsCommand
    data class CreateAxis(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId,
        val requestId: UUID? = null
    ) : PhysicsCommand
    data class CreateNoCollide(
        val dimension: PhysicsDimensionId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId,
        val requestId: UUID? = null
    ) : PhysicsCommand
    data class DestroyToolConstraints(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class ReplaceTerrainSection(
        val dimension: PhysicsDimensionId,
        val section: PhysicsSectionPosition,
        val geometry: List<TerrainBlockGeometry>,
        val revision: Long = 0
    ) : PhysicsCommand
    data class RemoveTerrainSection(
        val dimension: PhysicsDimensionId,
        val section: PhysicsSectionPosition
    ) : PhysicsCommand
    data class InvalidateTerrainSection(val dimension: PhysicsDimensionId, val section: PhysicsSectionPosition) : PhysicsCommand
    data class WakeBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class SuspendBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
    data class SuspendProp(val dimension: PhysicsDimensionId, val propId: UUID) : PhysicsCommand
    data class ResumeBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsCommand
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
    data class AssemblyTerrainBlocked(val propId: UUID, val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId) : PhysicsAcknowledgement
    data class ConnectionCreated(val requestId: UUID, val success: Boolean) : PhysicsAcknowledgement
    data class ConnectionPrepared(
        val requestId: UUID, val frames: PhysicsJointFrames?, val snapshots: Map<UUID, PhysicsBodySnapshot> = emptyMap()
    ) : PhysicsAcknowledgement
    data class ComponentParked(val requestId: UUID, val snapshots: Map<UUID, PhysicsBodySnapshot>, val success: Boolean) : PhysicsAcknowledgement
    data class ComponentStaged(val token: PhysicsRestoreToken, val bodies: List<ParkBody>, val success: Boolean) : PhysicsAcknowledgement
    data class ComponentActivated(val token: PhysicsRestoreToken, val success: Boolean) : PhysicsAcknowledgement

    data class BodyCreated(
        val propId: UUID,
        val dimension: PhysicsDimensionId,
        val bodyId: PhysicsBodyId,
        val requestId: UUID,
        val terrainBlocked: Boolean = false
    ) : PhysicsAcknowledgement

    data class BodyCreationFailed(val propId: UUID, val message: String, val requestId: UUID) : PhysicsAcknowledgement
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
