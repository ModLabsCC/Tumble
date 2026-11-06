// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.api

import cc.modlabs.tumble.physics.MinecraftTerrainAdapter
import cc.modlabs.tumble.physics.PhysicsBodySnapshot
import cc.modlabs.tumble.physics.PhysicsCommand
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.physicsDimensionId
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.commands.Commands
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/** Access to the running server's props. Call these methods on the Minecraft server thread. */
object TumbleApi {
    enum class PropStatus { UNAVAILABLE, PENDING, READY, PAUSED, FAILED }

    /** Returns the prop's ID, or null if it cannot be spawned. Physics creation is asynchronous. */
    @JvmStatic
    fun spawn(level: ServerLevel, position: Vec3, state: BlockState): UUID? {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        val vector = PhysicsVector(position.x, position.y, position.z)
        val block = BlockPos.containing(position)
        if (!level.isInWorldBounds(block) || !level.worldBorder.isWithinBounds(block) ||
            !level.chunkSource.hasChunk(block.x shr 4, block.z shr 4) || state.isAir || state.hasBlockEntity() ||
            MinecraftTerrainAdapter.dynamicShape(state.getCollisionShape(level, block).toAabbs()) == null
        ) return null
        val manager = PhysicsServices.runtime(level.server)?.manager ?: return null
        val prop = manager.spawn(level, vector, state, angularVelocity = PhysicsVector.ZERO) ?: return null
        if (!prop.physicsPending && prop.runtimeBodyId == null) {
            manager.entityUnloaded(prop, level)
            prop.discard()
            return null
        }
        return prop.uuid
    }

    /** Converts connected blocks after checking the player's permission for each position. */
    @JvmStatic
    fun convertAssembly(level: ServerLevel, positions: List<BlockPos>, player: ServerPlayer): UUID? {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        return PhysicsServices.runtime(level.server)?.manager?.convertAssembly(level, positions, player)?.uuid
    }

    /** Converts a larger selection for an operator, still checking protection for every block. */
    @JvmStatic
    fun convertAssemblyAsOperator(level: ServerLevel, positions: List<BlockPos>, player: ServerPlayer): UUID? {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        if (!Commands.hasPermission<CommandSourceStack>(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack())) return null
        return PhysicsServices.runtime(level.server)?.manager?.convertAssembly(level, positions, player, allowRemote = true)?.uuid
    }

    /** Returns the most recent snapshot, or null while the prop is unavailable or not ready. */
    @JvmStatic
    fun snapshot(level: ServerLevel, propId: UUID): PhysicsBodySnapshot? {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        val runtime = PhysicsServices.runtime(level.server) ?: return null
        val prop = runtime.manager.findProp(propId)?.takeIf { it.level() === level && !it.isRemoved } ?: return null
        val body = prop.runtimeBodyId ?: return null
        return runtime.service.latestFrame(level.physicsDimensionId()).bodies[body]
    }

    /** Reports body creation for a loaded prop. An unavailable prop may have been unloaded. */
    @JvmStatic
    fun status(level: ServerLevel, propId: UUID): PropStatus {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        val runtime = PhysicsServices.runtime(level.server) ?: return PropStatus.UNAVAILABLE
        val prop = runtime.manager.findProp(propId)?.takeIf { it.level() === level && !it.isRemoved }
            ?: return PropStatus.UNAVAILABLE
        if (!runtime.service.isRunning()) return PropStatus.FAILED
        return when {
            runtime.manager.isTerrainPaused(prop, level) -> PropStatus.PAUSED
            prop.runtimeBodyId != null -> PropStatus.READY
            prop.physicsPending -> PropStatus.PENDING
            else -> PropStatus.FAILED
        }
    }

    /** Queues an impulse; returns false if the prop is not ready or the service is stopping. */
    @JvmStatic
    fun applyImpulse(level: ServerLevel, propId: UUID, impulse: Vec3): Boolean {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        require(impulse.x.toFloat().isFinite() && impulse.y.toFloat().isFinite() && impulse.z.toFloat().isFinite()) {
            "Impulse components must be finite and fit in a float"
        }
        val vector = PhysicsVector(impulse.x, impulse.y, impulse.z)
        val runtime = PhysicsServices.runtime(level.server) ?: return false
        val prop = runtime.manager.findProp(propId)?.takeIf { it.level() === level && !it.isRemoved } ?: return false
        val body = prop.runtimeBodyId ?: return false
        if (prop.frozen || runtime.manager.isTerrainPaused(prop, level)) return false
        val mass = runtime.service.latestFrame(level.physicsDimensionId()).bodies[body]?.mass ?: return false
        require(Math.hypot(Math.hypot(impulse.x, impulse.y), impulse.z) <= mass.toDouble() * MAX_IMPULSE_SPEED_CHANGE) {
            "Impulse exceeds the maximum speed change of $MAX_IMPULSE_SPEED_CHANGE blocks per second"
        }
        return runtime.service.submit(PhysicsCommand.ApplyCentralImpulse(level.physicsDimensionId(), body, vector))
    }

    /** Removes a loaded prop. Returns false when the ID is not present in this level. */
    @JvmStatic
    fun remove(level: ServerLevel, propId: UUID): Boolean {
        check(level.server.isSameThread) { "Tumble API must be called on the server thread" }
        val manager = PhysicsServices.runtime(level.server)?.manager ?: return false
        val prop = manager.findProp(propId)?.takeIf { it.level() === level && !it.isRemoved } ?: return false
        if (prop.assembly != null && (prop.physicsPending || manager.isRecoveringAssembly(propId))) return false
        manager.entityUnloaded(prop, level)
        prop.discard()
        return true
    }

    private const val MAX_IMPULSE_SPEED_CHANGE = 64.0
}
