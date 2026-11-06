// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.util.IdentityHashMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import org.slf4j.Logger

object PhysicsServices {
    private val runtimes = Collections.synchronizedMap(IdentityHashMap<MinecraftServer, Runtime>())
    private val chunkIoFailures = Collections.synchronizedMap(IdentityHashMap<MinecraftServer, AtomicLong>())
    private val placementCapture = ThreadLocal<PlacementCapture?>()
    private val pistonCapture = ThreadLocal<PistonCapture?>()

    fun start(server: MinecraftServer, logger: Logger) {
        check(server !in runtimes) { "Physics service already exists for this server" }
        val service = ServerPhysicsService(logger).also(ServerPhysicsService::start)
        val terrain = TerrainSectionTracker(service)
        val manager = PhysicsPropManager(
            server,
            service,
            terrain,
            logger,
            TumbleTuning(FabricLoader.getInstance().configDir.resolve("tumble.properties"))
        )
        runtimes[server] = Runtime(service, terrain, manager, ServerPhysicsSnapshotPublisher(server, service, manager))
    }

    fun stop(server: MinecraftServer) {
        val runtime = runtimes.remove(server) ?: return
        closePhysicsRuntime(runtime.manager::checkpointConnections, runtime.publisher::close, runtime.service::close)
    }

    fun checkpointConnections(server: MinecraftServer) {
        runtimes[server]?.manager?.checkpointConnections()
    }

    fun stopped(server: MinecraftServer) {
        chunkIoFailures.remove(server)
    }

    fun levelLoaded(server: MinecraftServer, level: ServerLevel) {
        runtimes[server]?.service?.submit(PhysicsCommand.CreateDimension(level.physicsDimensionId()))
    }

    fun levelUnloaded(server: MinecraftServer, level: ServerLevel) {
        runtimes[server]?.service?.submit(PhysicsCommand.RemoveDimension(level.physicsDimensionId()))
    }

    fun recoverConversions(server: MinecraftServer) {
        runtimes[server]?.manager?.recoverPlacements()
        runtimes[server]?.manager?.recoverConversions()
    }

    fun chunkIoFailed(server: MinecraftServer) {
        synchronized(chunkIoFailures) { chunkIoFailures.getOrPut(server) { AtomicLong() } }.incrementAndGet()
    }

    internal fun chunkIoFailureCount(server: MinecraftServer): Long = chunkIoFailures[server]?.get() ?: 0L

    fun conversionWriteBlocked(level: ServerLevel, position: BlockPos, state: BlockState): Boolean =
        runtimes[level.server]?.manager?.conversionWriteBlocked(level, position, state) ?: false

    fun connectionRelationshipsBlocked(level: ServerLevel, id: UUID): Boolean =
        runtimes[level.server]?.manager?.connectionRelationshipsBlocked(id) ?: true

    fun assemblyConversionPending(level: ServerLevel, id: UUID): Boolean =
        runtimes[level.server]?.manager?.assemblyConversionPending(id) ?: false

    fun entityLoaded(entity: Entity, level: ServerLevel) {
        runtimes[level.server]?.manager?.let { manager ->
            if (entity is ItemEntity) manager.itemLoaded(entity, level)
            manager.entityLoaded(entity, level)
        }
    }

    fun entityUnloaded(entity: Entity, level: ServerLevel) {
        runtimes[level.server]?.let {
            if (entity is cc.modlabs.tumble.entity.PhysicsPropEntity) it.publisher.propRemoved(entity.uuid)
            it.manager.entityUnloaded(entity, level, visibilityEvent = true)
        }
    }

    fun beforePropRemoval(entity: cc.modlabs.tumble.entity.PhysicsPropEntity, level: ServerLevel): Boolean {
        val manager = runtimes[level.server]?.manager ?: return true
        if (manager.findProp(entity.uuid) !== entity) return true
        if (manager.isBlockPlacementPending(entity.uuid)) return false
        return manager.entityUnloaded(entity, level)
    }

    fun canTeleportProp(entity: cc.modlabs.tumble.entity.PhysicsPropEntity, level: ServerLevel, destination: ServerLevel): Boolean =
        runtimes[level.server]?.manager?.canTeleportProp(entity, level, destination) ?: true

    fun requestAssembly(player: ServerPlayer, propId: UUID) {
        runtimes[player.level().server]?.publisher?.requestAssembly(player, propId)
    }

    fun chunkUnloaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        runtimes[level.server]?.manager?.chunkUnloaded(level, chunkX, chunkZ)
    }

    fun chunkLoaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        runtimes[level.server]?.manager?.chunkLoaded(level, chunkX, chunkZ)
    }

    fun blockChanged(level: ServerLevel, position: BlockPos, previous: BlockState) {
        runtimes[level.server]?.terrain?.markBlockChanged(level, position)
        placementCapture.get()?.takeIf { capture ->
            capture.level === level && (previous.isAir || previous.canBeReplaced(capture.context))
        }?.positions?.add(position.immutable())
    }

    fun beginBlockPlacement(level: ServerLevel, context: BlockPlaceContext): Boolean {
        if (placementCapture.get() != null) return false
        placementCapture.set(PlacementCapture(level, context))
        return true
    }

    fun endBlockPlacement(level: ServerLevel, successful: Boolean) {
        val capture = placementCapture.get()
        placementCapture.remove()
        if (successful && capture?.level === level && capture.positions.isNotEmpty()) {
            runtimes[level.server]?.manager?.blocksPlaced(level, capture.positions)
        }
    }

    fun beginPistonMove(
        level: ServerLevel,
        pistonPosition: BlockPos,
        direction: Direction,
        extending: Boolean,
        movedBlocks: List<BlockPos>
    ): Boolean {
        if (pistonCapture.get() != null) return false
        val movementDirection = if (extending) direction else direction.opposite
        val movement = PhysicsVector(
            movementDirection.stepX.toDouble(),
            movementDirection.stepY.toDouble(),
            movementDirection.stepZ.toDouble()
        )
        val boxes = ArrayList<cc.modlabs.tumble.physics.PhysicsBox>()
        movedBlocks.forEach { source ->
            val target = source.relative(movementDirection)
            val state = level.getBlockState(source)
            boxes += MinecraftTerrainAdapter.boxesAt(
                target,
                state.getCollisionShape(level, source).toAabbs(),
                MinecraftTerrainAdapter.material(state)
            ).map { it.bounds }
            if (boxes.size > MAX_PISTON_BOXES) return false
        }
        if (extending) {
            val head = pistonPosition.relative(direction)
            boxes += cc.modlabs.tumble.physics.PhysicsBox(
                PhysicsVector(head.x.toDouble(), head.y.toDouble(), head.z.toDouble()),
                PhysicsVector(head.x + 1.0, head.y + 1.0, head.z + 1.0)
            )
        }
        if (boxes.isEmpty()) return false
        pistonCapture.set(PistonCapture(level, boxes, movement))
        return true
    }

    fun endPistonMove(successful: Boolean) {
        val capture = pistonCapture.get()
        pistonCapture.remove()
        if (successful && capture != null) {
            runtimes[capture.level.server]?.manager?.pistonMoved(
                capture.level,
                capture.boxes,
                capture.movement
            )
        }
    }

    fun clipActorMovement(actor: LivingEntity, movement: Vec3): Vec3 {
        val level = actor.level() as? ServerLevel ?: return movement
        return runtimes[level.server]?.manager?.clipActorMovement(actor, movement) ?: movement
    }

    fun requestPush(player: ServerPlayer, propId: UUID, movement: PhysicsVector) {
        runtimes[player.level().server]?.manager?.requestActorPush(player, propId, movement)
    }

    fun controlPhysicsGun(
        player: ServerPlayer,
        propId: UUID,
        distanceDelta: Float,
        yawDelta: Float,
        pitchDelta: Float
    ) {
        runtimes[player.level().server]?.manager?.controlPhysicsGun(
            player, propId, distanceDelta, yawDelta, pitchDelta
        )
    }

    fun launchGravityGun(player: ServerPlayer): Boolean {
        if (!player.isHolding { it.item === cc.modlabs.tumble.item.TumbleItems.GRAVITY_GUN }) return false
        val launched = runtimes[player.level().server]?.manager?.launchHeld(player) ?: false
        if (launched) {
            player.level().playSound(
                null, player.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.6f, 0.8f
            )
        }
        return launched
    }

    fun togglePhysicsGunHeld(player: Player, prop: cc.modlabs.tumble.entity.PhysicsPropEntity): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        return runtimes[level.server]?.manager?.togglePhysicsGunHeld(player, prop) ?: false
    }

    fun usePhysicsGunSecondary(
        player: Player,
        prop: cc.modlabs.tumble.entity.PhysicsPropEntity,
        changeToBlock: Boolean
    ): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        return runtimes[level.server]?.manager?.usePhysicsGunSecondary(player, prop, changeToBlock) ?: false
    }

    fun useToolGunWeld(player: Player, prop: cc.modlabs.tumble.entity.PhysicsPropEntity): ToolGunWeldResult {
        val level = player.level() as? ServerLevel ?: return ToolGunWeldResult.FAILED
        return runtimes[level.server]?.manager?.useToolGunWeld(player, prop) ?: ToolGunWeldResult.FAILED
    }

    fun useToolGun(player: Player, prop: cc.modlabs.tumble.entity.PhysicsPropEntity): ToolGunResult {
        val level = player.level() as? ServerLevel ?: return ToolGunResult.FAILED
        return runtimes[level.server]?.manager?.useToolGun(player, prop) ?: ToolGunResult.FAILED
    }

    fun useToolGunPrimary(player: Player, prop: cc.modlabs.tumble.entity.PhysicsPropEntity): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        return runtimes[level.server]?.manager?.useToolGunPrimary(player, prop) ?: false
    }

    fun removeToolGunWelds(player: Player, prop: cc.modlabs.tumble.entity.PhysicsPropEntity): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        return runtimes[level.server]?.manager?.removeToolGunWelds(player, prop) ?: false
    }

    fun undoToolGunWeld(player: Player): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        return runtimes[level.server]?.manager?.undoToolGunWeld(player) ?: false
    }

    fun cycleToolGunMode(player: Player, delta: Int) {
        val level = player.level() as? ServerLevel ?: return
        runtimes[level.server]?.manager?.cycleToolGunMode(player, delta)
    }

    fun actorCollisionBounds(actor: LivingEntity): AABB =
        if (actor is Player && actor.boundingBox.ysize > PLAYER_COLLISION_TRIM) {
            actor.boundingBox.contract(0.0, PLAYER_COLLISION_TRIM, 0.0)
        } else {
            actor.boundingBox
        }

    fun explosion(level: ServerLevel, origin: Vec3) {
        runtimes[level.server]?.manager?.explosion(level, PhysicsVector(origin.x, origin.y, origin.z))
    }

    fun convertExplodedBlocks(level: ServerLevel, origin: Vec3, positions: List<BlockPos>): List<BlockPos> =
        runtimes[level.server]?.manager?.convertExplodedBlocks(
            level,
            PhysicsVector(origin.x, origin.y, origin.z),
            positions
        ) ?: positions

    fun tick(server: MinecraftServer) {
        runtimes[server]?.let {
            it.manager.tick()
            it.publisher.tick()
        }
    }

    fun trackingStarted(entity: Entity, player: ServerPlayer) {
        if (entity is cc.modlabs.tumble.entity.PhysicsPropEntity) {
            runtimes[player.level().server]?.publisher?.startTracking(entity, player)
        }
    }

    fun trackingStopped(entity: Entity, player: ServerPlayer) {
        if (entity is cc.modlabs.tumble.entity.PhysicsPropEntity) {
            runtimes[player.level().server]?.publisher?.stopTracking(entity, player)
        }
    }

    fun playerDisconnected(player: ServerPlayer) {
        runtimes[player.level().server]?.let {
            it.manager.releasePlayer(player.uuid)
            it.publisher.playerDisconnected(player.uuid)
        }
    }

    fun togglePhysicsDebug(player: ServerPlayer): Boolean =
        runtimes[player.level().server]?.publisher?.toggleDebug(player) ?: false

    fun enablePhysicsDebug(player: ServerPlayer) {
        runtimes[player.level().server]?.publisher?.enableDebug(player)
    }

    fun runtime(server: MinecraftServer): Runtime? = runtimes[server]

    data class Runtime(
        val service: ServerPhysicsService,
        val terrain: TerrainSectionTracker,
        val manager: PhysicsPropManager,
        val publisher: ServerPhysicsSnapshotPublisher
    )

    private data class PlacementCapture(
        val level: ServerLevel,
        val context: BlockPlaceContext,
        val positions: MutableSet<BlockPos> = linkedSetOf()
    )

    private data class PistonCapture(
        val level: ServerLevel,
        val boxes: List<cc.modlabs.tumble.physics.PhysicsBox>,
        val movement: PhysicsVector
    )

    private const val MAX_PISTON_BOXES = 128
    private const val PLAYER_COLLISION_TRIM = 2.0 / 16.0
}


internal fun closePhysicsRuntime(checkpoint: () -> Unit, closePublisher: () -> Unit, closeService: () -> Unit) {
    var failure: Throwable? = null
    for (action in listOf(checkpoint, closePublisher, closeService)) {
        try {
            action()
        } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
