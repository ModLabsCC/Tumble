// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import cc.modlabs.tumble.TumbleMod
import cc.modlabs.tumble.physics.PhysicsDimensionId
import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import net.minecraft.core.Direction
import cc.modlabs.tumble.physics.ToolGunMode
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

data class PhysicsPropNetworkState(
    val propId: UUID,
    val bodyGeneration: Long,
    val position: PhysicsVector,
    val rotation: PhysicsRotation,
    val linearVelocity: PhysicsVector,
    val angularVelocity: PhysicsVector,
    val sleeping: Boolean,
    val flags: Int
) {
    init {
        require(bodyGeneration > 0) { "Body generation must be positive" }
        require(flags and PhysicsStateFlags.KNOWN.inv() == 0) { "Unknown physics state flags: $flags" }
    }
}

object PhysicsStateFlags {
    const val RESET = 1
    const val SLEEP_TRANSITION = 1 shl 1
    const val WAKE_TRANSITION = 1 shl 2
    const val KNOWN = RESET or SLEEP_TRANSITION or WAKE_TRANSITION
}

data class PhysicsSnapshotPayload(
    val dimension: PhysicsDimensionId,
    val serverPhysicsStep: Long,
    val serverGameTick: Long,
    val publicationSequence: Long,
    val states: List<PhysicsPropNetworkState>
) : CustomPacketPayload {
    init {
        require(serverPhysicsStep >= 0 && serverGameTick >= 0 && publicationSequence > 0)
        require(states.size <= MAX_STATES) { "Physics snapshot exceeds $MAX_STATES states" }
    }

    override fun type() = TYPE

    companion object {
        const val MAX_STATES = 128
        val TYPE = CustomPacketPayload.Type<PhysicsSnapshotPayload>(TumbleMod.id("physics_snapshot"))
        val CODEC: StreamCodec<FriendlyByteBuf, PhysicsSnapshotPayload> = object : StreamCodec<FriendlyByteBuf, PhysicsSnapshotPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: PhysicsSnapshotPayload) {
                buffer.writeUtf(payload.dimension.value, 128)
                buffer.writeLong(payload.serverPhysicsStep)
                buffer.writeLong(payload.serverGameTick)
                buffer.writeLong(payload.publicationSequence)
                buffer.writeVarInt(payload.states.size)
                payload.states.forEach { buffer.writeState(it) }
            }

            override fun decode(buffer: FriendlyByteBuf): PhysicsSnapshotPayload {
                val dimension = PhysicsDimensionId(buffer.readUtf(128))
                val physicsStep = buffer.readLong()
                val gameTick = buffer.readLong()
                val sequence = buffer.readLong()
                val count = buffer.readVarInt()
                require(count in 0..MAX_STATES) { "Invalid physics state count: $count" }
                return PhysicsSnapshotPayload(
                    dimension,
                    physicsStep,
                    gameTick,
                    sequence,
                    List(count) { buffer.readState() }
                )
            }
        }
    }
}

data class PhysicsDespawnPayload(val propId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PhysicsDespawnPayload>(TumbleMod.id("physics_despawn"))
        val CODEC: StreamCodec<FriendlyByteBuf, PhysicsDespawnPayload> = object : StreamCodec<FriendlyByteBuf, PhysicsDespawnPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: PhysicsDespawnPayload) = buffer.writeUUID(payload.propId).let { Unit }
            override fun decode(buffer: FriendlyByteBuf) = PhysicsDespawnPayload(buffer.readUUID())
        }
    }
}

data class PhysicsPushPayload(val propId: UUID, val movement: PhysicsVector) : CustomPacketPayload {
    init {
        require(movement.squaredLength() in 1e-12..MAX_MOVEMENT_SQUARED) {
            "Invalid physics push movement"
        }
    }

    override fun type() = TYPE

    companion object {
        private const val MAX_MOVEMENT_SQUARED = 1.0
        val TYPE = CustomPacketPayload.Type<PhysicsPushPayload>(TumbleMod.id("physics_push"))
        val CODEC: StreamCodec<FriendlyByteBuf, PhysicsPushPayload> = object : StreamCodec<FriendlyByteBuf, PhysicsPushPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: PhysicsPushPayload) {
                buffer.writeUUID(payload.propId)
                buffer.writeVector(payload.movement)
            }

            override fun decode(buffer: FriendlyByteBuf) = PhysicsPushPayload(buffer.readUUID(), buffer.readVector())
        }
    }
}

data class PhysicsGunControlPayload(
    val propId: UUID,
    val distanceDelta: Float,
    val yawDelta: Float,
    val pitchDelta: Float
) : CustomPacketPayload {
    init {
        require(distanceDelta.isFinite() && distanceDelta in -4f..4f) { "Invalid physics gun distance delta" }
        require(yawDelta.isFinite() && pitchDelta.isFinite() &&
            yawDelta in -45f..45f && pitchDelta in -45f..45f) { "Invalid physics gun rotation delta" }
        require(distanceDelta != 0f || yawDelta != 0f || pitchDelta != 0f) { "Empty physics gun control" }
    }

    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PhysicsGunControlPayload>(TumbleMod.id("physics_gun_control"))
        val CODEC: StreamCodec<FriendlyByteBuf, PhysicsGunControlPayload> = object : StreamCodec<FriendlyByteBuf, PhysicsGunControlPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: PhysicsGunControlPayload) {
                buffer.writeUUID(payload.propId)
                buffer.writeFloat(payload.distanceDelta)
                buffer.writeFloat(payload.yawDelta)
                buffer.writeFloat(payload.pitchDelta)
            }

            override fun decode(buffer: FriendlyByteBuf) = PhysicsGunControlPayload(
                buffer.readUUID(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat()
            )
        }
    }
}

data object GravityGunLaunchPayload : CustomPacketPayload {
    override fun type() = TYPE

    val TYPE = CustomPacketPayload.Type<GravityGunLaunchPayload>(TumbleMod.id("gravity_gun_launch"))
    val CODEC: StreamCodec<FriendlyByteBuf, GravityGunLaunchPayload> =
        object : StreamCodec<FriendlyByteBuf, GravityGunLaunchPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: GravityGunLaunchPayload) = Unit
            override fun decode(buffer: FriendlyByteBuf) = GravityGunLaunchPayload
        }
}

data class ToolGunStatePayload(
    val mode: ToolGunMode,
    val selectedPropId: UUID?,
    val selectedFace: Direction?,
    val canUndo: Boolean
) : CustomPacketPayload {
    init {
        require((selectedPropId == null) == (selectedFace == null)) { "Tool gun prop and face must be selected together" }
    }

    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ToolGunStatePayload>(TumbleMod.id("tool_gun_state"))
        val CODEC: StreamCodec<FriendlyByteBuf, ToolGunStatePayload> = object : StreamCodec<FriendlyByteBuf, ToolGunStatePayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: ToolGunStatePayload) {
                buffer.writeByte(payload.mode.ordinal)
                buffer.writeBoolean(payload.selectedPropId != null)
                payload.selectedPropId?.let(buffer::writeUUID)
                payload.selectedFace?.let { buffer.writeByte(it.get3DDataValue()) }
                buffer.writeBoolean(payload.canUndo)
            }

            override fun decode(buffer: FriendlyByteBuf): ToolGunStatePayload {
                val mode = ToolGunMode.entries.getOrElse(buffer.readUnsignedByte().toInt()) { ToolGunMode.WELD }
                val selected = buffer.readBoolean()
                return ToolGunStatePayload(
                    mode,
                    if (selected) buffer.readUUID() else null,
                    if (selected) Direction.from3DDataValue(buffer.readUnsignedByte().toInt()) else null,
                    buffer.readBoolean()
                )
            }
        }
    }
}

data class ToolGunModePayload(val delta: Int) : CustomPacketPayload {
    init {
        require(delta == -1 || delta == 1) { "Tool gun mode delta must be -1 or 1" }
    }

    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ToolGunModePayload>(TumbleMod.id("tool_gun_mode"))
        val CODEC: StreamCodec<FriendlyByteBuf, ToolGunModePayload> = object : StreamCodec<FriendlyByteBuf, ToolGunModePayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: ToolGunModePayload) {
                buffer.writeByte(payload.delta)
            }
            override fun decode(buffer: FriendlyByteBuf) = ToolGunModePayload(buffer.readByte().toInt())
        }
    }
}

data class PhysicsDebugPayload(
    val props: Int,
    val bodies: Int,
    val queuedCommands: Int,
    val averageWorkMillis: Double,
    val statesSent: Int,
    val payloadsSent: Int,
    val sleepingOmitted: Int
) : CustomPacketPayload {
    init {
        require(listOf(props, bodies, queuedCommands, statesSent, payloadsSent, sleepingOmitted).all { it in 0..MAX_COUNT }) {
            "Invalid physics debug count"
        }
        require(averageWorkMillis.isFinite() && averageWorkMillis in 0.0..MAX_WORK_MILLIS) {
            "Invalid physics debug work time"
        }
    }

    override fun type() = TYPE

    companion object {
        private const val MAX_COUNT = 1_000_000
        private const val MAX_WORK_MILLIS = 60_000.0
        val TYPE = CustomPacketPayload.Type<PhysicsDebugPayload>(TumbleMod.id("physics_debug"))
        val CODEC: StreamCodec<FriendlyByteBuf, PhysicsDebugPayload> =
            object : StreamCodec<FriendlyByteBuf, PhysicsDebugPayload> {
                override fun encode(buffer: FriendlyByteBuf, payload: PhysicsDebugPayload) {
                    buffer.writeVarInt(payload.props)
                    buffer.writeVarInt(payload.bodies)
                    buffer.writeVarInt(payload.queuedCommands)
                    buffer.writeDouble(payload.averageWorkMillis)
                    buffer.writeVarInt(payload.statesSent)
                    buffer.writeVarInt(payload.payloadsSent)
                    buffer.writeVarInt(payload.sleepingOmitted)
                }

                override fun decode(buffer: FriendlyByteBuf) = PhysicsDebugPayload(
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readDouble(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
                )
            }
    }
}

private fun FriendlyByteBuf.writeState(state: PhysicsPropNetworkState) {
    writeUUID(state.propId)
    writeLong(state.bodyGeneration)
    writeVector(state.position)
    writeDouble(state.rotation.x)
    writeDouble(state.rotation.y)
    writeDouble(state.rotation.z)
    writeDouble(state.rotation.w)
    writeVector(state.linearVelocity)
    writeVector(state.angularVelocity)
    writeBoolean(state.sleeping)
    writeInt(state.flags)
}

private fun FriendlyByteBuf.readState() = PhysicsPropNetworkState(
    readUUID(),
    readLong(),
    readVector(),
    PhysicsRotation(readDouble(), readDouble(), readDouble(), readDouble()),
    readVector(),
    readVector(),
    readBoolean(),
    readInt()
)

private fun FriendlyByteBuf.writeVector(vector: PhysicsVector) {
    writeDouble(vector.x)
    writeDouble(vector.y)
    writeDouble(vector.z)
}

private fun FriendlyByteBuf.readVector() = PhysicsVector(readDouble(), readDouble(), readDouble())
