// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.TumbleGameRules
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.entity.ToolPhysicsPreset
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.assembly.AssemblyBlock
import cc.modlabs.tumble.assembly.AssemblyDefinition
import cc.modlabs.tumble.assembly.AssemblyGeometry
import cc.modlabs.tumble.network.ToolGunStatePayload
import cc.modlabs.tumble.physics.PhysicsBodyDefinition
import cc.modlabs.tumble.physics.PhysicsBodyId
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsShapeDefinition
import cc.modlabs.tumble.physics.PhysicsTransform
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import java.nio.file.Path
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.Logger

internal class PropBodyAssociations {
    private val activeProps = mutableMapOf<UUID, UUID>()
    private val bodies = mutableMapOf<UUID, PhysicsBodyId>()

    fun register(propId: UUID): UUID = UUID.randomUUID().also { activeProps[propId] = it }

    fun requestId(propId: UUID): UUID = activeProps.getValue(propId)

    fun isCurrent(propId: UUID, requestId: UUID): Boolean = activeProps[propId] == requestId

    fun acknowledge(propId: UUID, requestId: UUID, bodyId: PhysicsBodyId): Boolean {
        if (!isCurrent(propId, requestId)) return false
        bodies[propId] = bodyId
        return true
    }

    fun remove(propId: UUID): PhysicsBodyId? {
        activeProps.remove(propId)
        return bodies.remove(propId)
    }
}

class PhysicsPropManager(
    private val server: MinecraftServer,
    private val service: ServerPhysicsService,
    private val terrain: TerrainSectionTracker,
    private val logger: Logger,
    private val tuning: TumbleTuning,
    journalDirectory: Path = server.getWorldPath(LevelResource.ROOT).resolve("tumble-assembly-journal")
) {
    private val props = mutableMapOf<UUID, PhysicsPropEntity>()
    private val assemblies = mutableMapOf<UUID, PhysicsPropEntity>()
    private val terrainWaits = mutableMapOf<UUID, MutableSet<Long>>()
    private val unavailableTerrain = mutableMapOf<ServerLevel, MutableSet<Long>>()
    private val unloadedTerrainWaits = mutableListOf<UnloadedTerrainWait>()
    private val deferredAssemblies = ArrayDeque<UUID>()
    private val deferredTerrainProps = ArrayDeque<UUID>()
    private val associations = PropBodyAssociations()
    private val bodyGenerations = mutableMapOf<UUID, Long>()
    private val bodyOwners = mutableMapOf<RuntimeBodyKey, UUID>()
    private val pendingActorImpulses = mutableMapOf<UUID, PhysicsVector>()
    private val lastPushRequestTick = mutableMapOf<UUID, Long>()
    private val pendingExplosionVelocity = mutableMapOf<UUID, PhysicsVector>()
    private val submersionByProp = mutableMapOf<UUID, Float>()
    private val heldByPlayer = mutableMapOf<UUID, UUID>()
    private val heldByProp = mutableMapOf<UUID, UUID>()
    private val heldLivingByPlayer = mutableMapOf<UUID, LivingEntity>()
    private val heldLivingEntities = mutableSetOf<UUID>()
    private val physicsGunHoldDistance = mutableMapOf<UUID, Double>()
    private val weldSelectionByPlayer = mutableMapOf<UUID, SelectedWeld>()
    private val toolModeByPlayer = mutableMapOf<UUID, ToolGunMode>()
    private val activeWelds = mutableSetOf<WeldKey>()
    private val toolConnections = mutableSetOf<WeldKey>()
    private val confirmedToolConnections = mutableSetOf<WeldKey>()
    private val pendingConnections = mutableMapOf<UUID, PendingConnection>()
    private val weldHistoryByPlayer = mutableMapOf<UUID, java.util.ArrayDeque<WeldKey>>()
    private val lastPhysicsGunControlTick = mutableMapOf<UUID, Long>()
    private val launchedProps = mutableMapOf<UUID, LaunchedProp>()
    private val pendingPhysicsGunHolds = mutableMapOf<UUID, UUID>()
    private val pendingConversions = mutableMapOf<UUID, List<ConvertedBlock>>()
    private val conversionJournal = AssemblyConversionJournal(journalDirectory)
    private val journalRecords = conversionJournal.pending().toMutableMap()
    private val recoveryRecords = journalRecords.mapValuesTo(mutableMapOf()) { AssemblyRecoveryRecord.decode(it.value.payload) }
    private val journalFailureEpoch = recoveryRecords.keys.associateWithTo(mutableMapOf()) { 0L }
    private val conversionLocks = mutableMapOf<Pair<String, BlockPos>, UUID>()
    private var conversionWritePermit: Pair<UUID, Pair<String, BlockPos>>? = null
    private val failedAssemblyConversions = linkedSetOf<UUID>()
    private val pendingItemConversions = mutableMapOf<UUID, ConvertedItem>()
    private val pendingPlacedBlocks = linkedSetOf<PendingPlacedBlock>()
    private val pendingDroppedItems = linkedMapOf<UUID, Pair<ServerLevel, ItemEntity>>()
    private val ignoredDroppedItems = mutableSetOf<UUID>()
    private val skipTerrainPrime = mutableSetOf<UUID>()
    private var pendingActorContacts = 0
    private var carryingActors = false
    private var managerTick = 0L
    var actorContactsLastTick = 0
        private set
    var actorImpulsesLastTick = 0
        private set
    var explosionPropsLastTick = 0
        private set

    init {
        recoveryRecords.forEach { (id, record) -> lockConversion(id, record) }
        submitNativeTuning()
    }

    fun canSpawn() = props.size < tuning.get(TumbleTuningKey.MAX_PHYSICS_OBJECTS).toInt()

    fun spawn(
        level: ServerLevel,
        position: PhysicsVector,
        blockState: BlockState = Blocks.RAW_COPPER_BLOCK.defaultBlockState(),
        linearVelocity: PhysicsVector = PhysicsVector.ZERO,
        angularVelocity: PhysicsVector = PhysicsVector(1.8, 2.4, 1.2),
        primeTerrain: Boolean = true
    ): PhysicsPropEntity? {
        if (!canSpawn()) return null
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, level).apply {
            setPos(position.x, position.y, position.z)
            setDeltaMovement(linearVelocity.x, linearVelocity.y, linearVelocity.z)
            initialize(blockState, angularVelocity)
        }
        if (!primeTerrain) skipTerrainPrime += entity.uuid
        if (!level.addFreshEntity(entity)) {
            skipTerrainPrime.remove(entity.uuid)
            return null
        }
        entityLoaded(entity, level)
        return entity
    }

    fun convertBlock(
        level: ServerLevel,
        position: BlockPos,
        player: ServerPlayer,
        linearVelocity: PhysicsVector = PhysicsVector.ZERO
    ): PhysicsPropEntity? {
        if (player.level() !== level || !level.chunkSource.hasChunk(position.x shr 4, position.z shr 4) ||
            !level.worldBorder.isWithinBounds(position) || !player.mayInteract(level, position) ||
            player.blockActionRestricted(level, position, player.gameMode())
        ) return null
        val state = level.getBlockState(position)
        if (!net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker()
                .beforeBlockBreak(level, player, position, state, level.getBlockEntity(position))) return null
        return convertBlocks(level, listOf(BlockConversion(position.immutable(), linearVelocity)))[position]
    }

    internal fun convertAssembly(level: ServerLevel, positions: List<BlockPos>, player: ServerPlayer, allowRemote: Boolean = false): PhysicsPropEntity? {
        if (!service.isRunning() || PhysicsServices.chunkIoFailureCount(server) != 0L ||
            player.level() !== level || !player.isAlive || player.isSpectator || !player.mayBuild() || !canSpawn() ||
            positions.isEmpty() || positions.size > AssemblyDefinition.MAX_BLOCKS
        ) return null
        val selected = positions.map { it.immutable() }
        if (selected.isEmpty() || selected.size > AssemblyDefinition.MAX_BLOCKS || selected.toSet().size != selected.size ||
            selected.any { (level.physicsDimensionId().value to it) in conversionLocks }
        ) return null
        val origin = selected.first()
        if (player.distanceToSqr(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5) > PHYSICS_GUN_REACH_SQUARED) return null
        val remaining = selected.toMutableSet()
        val queue = ArrayDeque<BlockPos>()
        queue.add(origin)
        remaining.remove(origin)
        while (queue.isNotEmpty()) {
            val position = queue.removeFirst()
            net.minecraft.core.Direction.entries.forEach { direction ->
                val neighbour = position.relative(direction)
                if (remaining.remove(neighbour)) queue.add(neighbour)
            }
        }
        if (remaining.isNotEmpty()) return null

        val conversionId = UUID.randomUUID()
        val keys = selected.map { level.physicsDimensionId().value to it }
        keys.forEach { conversionLocks[it] = conversionId }
        return try {
            convertAssemblyLocked(level, selected, player, allowRemote, origin, conversionId)
        } finally {
            if (conversionId !in recoveryRecords) keys.forEach { conversionLocks.remove(it, conversionId) }
        }
    }

    private fun convertAssemblyLocked(
        level: ServerLevel,
        positions: List<BlockPos>,
        player: ServerPlayer,
        allowRemote: Boolean,
        origin: BlockPos,
        conversionId: UUID
    ): PhysicsPropEntity? {
        val captured = ArrayList<ConvertedBlock>(positions.size)
        positions.forEach { position ->
            if (!level.isInWorldBounds(position) || !level.chunkSource.hasChunk(position.x shr 4, position.z shr 4) ||
                !level.worldBorder.isWithinBounds(position) || !player.mayInteract(level, position) ||
                (!allowRemote && !player.isWithinBlockInteractionRange(position, 0.0)) ||
                player.blockActionRestricted(level, position, player.gameMode())
            ) return null
            val state = level.getBlockState(position)
            if (state.isAir || state.hasBlockEntity() || state.getDestroySpeed(level, position) < 0f ||
                !net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.invoker()
                    .beforeBlockBreak(level, player, position, state, level.getBlockEntity(position))
            ) return null
            captured += ConvertedBlock(level, position.immutable(), state)
        }
        val structure = try {
            val definition = AssemblyDefinition.of(captured.map { AssemblyBlock(it.position.subtract(origin), it.state) })
            AssemblyGeometry.capture(level, origin, definition)
        } catch (error: IllegalArgumentException) {
            logger.debug("Cannot capture assembly: {}", error.message)
            return null
        }
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, level).apply {
            setUUID(conversionId)
            setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
            initializeAssembly(structure)
            recoveryOrigin = origin
        }
        val record = AssemblyRecoveryRecord(level.physicsDimensionId().value, origin, structure.definition, fenced = true, passengerSafe = true)
        try {
            conversionJournal.prepare(entity.uuid, record.encode())
        } catch (error: Exception) {
            logger.error("Could not journal assembly conversion at {}", origin, error)
            return null
        }
        if (PhysicsServices.chunkIoFailureCount(server) != 0L) {
            conversionJournal.complete(entity.uuid)
            return null
        }
        recoveryRecords[entity.uuid] = record
        journalFailureEpoch[entity.uuid] = 0L
        lockConversion(entity.uuid, record)
        try {
            conversionJournal.markStarted(entity.uuid)
        } catch (error: Exception) {
            server.halt(false)
            throw error
        }
        journalRecords[entity.uuid] = AssemblyConversionJournal.Pending(record.encode(), true)
        if (!level.addFreshEntity(entity)) {
            conversionJournal.complete(entity.uuid)
            recoveryRecords.remove(entity.uuid)
            journalRecords.remove(entity.uuid)
            journalFailureEpoch.remove(entity.uuid)
            unlockConversion(entity.uuid, record)
            return null
        }
        entityLoaded(entity, level)
        failedAssemblyConversions.remove(entity.uuid)
        val removed = ArrayList<ConvertedBlock>(captured.size)
        var removalFailed = false
        try {
            for (block in captured) {
                if (level.getBlockState(block.position) != block.state) {
                    removalFailed = true
                    break
                }
                removed += block
                if (!setConversionBlock(block, Blocks.AIR.defaultBlockState(), entity.uuid)) {
                    removalFailed = true
                    break
                }
            }
            if (!removalFailed) {
                terrain.primeForPositions(level, positions)
                activateEntity(entity, level)
            }
        } catch (error: Exception) {
            logger.error("Failed to convert assembly at {}", origin, error)
            removalFailed = true
        }
        if (removalFailed || !entity.physicsPending && entity.runtimeBodyId == null) {
            abortAssemblyConversion(entity, level, removed)
            return null
        }
        return entity
    }

    private fun abortAssemblyConversion(entity: PhysicsPropEntity, level: ServerLevel, removed: List<ConvertedBlock>) {
        pendingConversions[entity.uuid] = removed.toList()
        retainFailedAssembly(entity)
        if (rollbackPendingConversion(entity.uuid)) {
            entityUnloaded(entity, level)
            entity.discard()
            checkpointConversion(entity.uuid, level)
        }
    }

    private fun lockConversion(id: UUID, record: AssemblyRecoveryRecord) {
        record.definition.blocks.forEach {
            val key = record.dimension to record.origin.offset(it.offset)
            val previous = conversionLocks.putIfAbsent(key, id)
            check(previous == null || previous == id) { "Overlapping assembly recovery journals" }
        }
    }

    private fun unlockConversion(id: UUID, record: AssemblyRecoveryRecord) {
        record.definition.blocks.forEach { conversionLocks.remove(record.dimension to record.origin.offset(it.offset), id) }
    }

    fun conversionWriteBlocked(level: ServerLevel, position: BlockPos, state: BlockState): Boolean {
        if (conversionLocks.isEmpty()) return false
        val key = level.physicsDimensionId().value to position
        val owner = conversionLocks[key] ?: return false
        if (conversionWritePermit == (owner to key)) {
            conversionWritePermit = null
            return false
        }
        val record = recoveryRecords[owner] ?: return true
        val expected = record.definition.blocks.firstOrNull { record.origin.offset(it.offset) == position }?.state ?: return true
        val current = level.getBlockState(position)
        if (state.isAir && !current.isAir && current != expected) return false
        return true
    }

    fun assemblyConversionPending(id: UUID): Boolean = recoveryRecords[id]?.passengerSafe == true

    private fun setConversionBlock(block: ConvertedBlock, state: BlockState, owner: UUID): Boolean {
        check(conversionWritePermit == null)
        conversionWritePermit = owner to (block.level.physicsDimensionId().value to block.position)
        return try { block.level.setBlock(block.position, state, CONVERSION_FLAGS) }
        finally { conversionWritePermit = null }
    }

    private fun checkpointConversion(id: UUID, level: ServerLevel) {
        if (id !in recoveryRecords) return
        try {
            val failures = journalFailureEpoch.getValue(id)
            check(PhysicsServices.chunkIoFailureCount(server) == failures) { "A chunk I/O failure occurred during assembly conversion $id; journal retained" }
            level.save(null, true, false)
            check(PhysicsServices.chunkIoFailureCount(server) == failures) { "Could not save assembly conversion $id; journal retained" }
            conversionJournal.complete(id)
            recoveryRecords.remove(id)?.let { unlockConversion(id, it) }
            journalRecords.remove(id)
            journalFailureEpoch.remove(id)
        } catch (error: Exception) {
            server.halt(false)
            throw error
        }
    }

    internal fun recoverConversions() {
        for ((id, record) in recoveryRecords.toMap()) {
            val level = server.allLevels.firstOrNull { it.physicsDimensionId().value == record.dimension }
                ?: error("Missing dimension for assembly conversion $id")
            val failures = PhysicsServices.chunkIoFailureCount(server)
            check(failures == 0L) { "A chunk I/O failure occurred before assembly recovery $id" }
            val chunks = record.definition.blocks.map {
                val position = record.origin.offset(it.offset)
                ChunkPos(position.x shr 4, position.z shr 4)
            }.distinct()
            val newlyForced = chunks.filter { !level.forceLoadedChunks.contains(it.pack()) }
            try {
                chunks.forEach { chunk ->
                    level.setChunkForced(chunk.x, chunk.z, true)
                    level.getChunk(chunk.x, chunk.z)
                }
                level.waitForEntities(ChunkPos(record.origin.x shr 4, record.origin.z shr 4), 0)
                check(PhysicsServices.chunkIoFailureCount(server) == failures) { "Could not load chunks for assembly conversion $id" }
                val found = level.getEntityInAnyDimension(id)
                if (journalRecords.getValue(id).started.not()) {
                    check(found == null && record.definition.blocks.all {
                        level.getBlockState(record.origin.offset(it.offset)) == it.state
                    }) { "Unstarted assembly conversion $id no longer matches its originals" }
                    conversionJournal.complete(id)
                    recoveryRecords.remove(id)
                    journalRecords.remove(id)
                    journalFailureEpoch.remove(id)
                    unlockConversion(id, record)
                    continue
                }
                if (found == null) {
                    check(record.passengerSafe) { "Assembly $id may be saved as an unloaded passenger; journal retained" }
                    // shortcut: external teleports can move a pending entity elsewhere; fence movement before supporting them.
                    check(restoreJournalBlocks(id, record, level)) { "Assembly recovery for $id is obstructed; journal retained" }
                    checkpointConversion(id, level)
                    continue
                }
                val entity = found as? PhysicsPropEntity
                    ?: error("Entity for assembly conversion $id has the wrong type; journal retained")
                check(entity.level() === level && entity.assembly?.definition?.blocks == record.definition.blocks) {
                    "Assembly entity for $id does not match its journal"
                }
                check(!entity.isPassenger && entity.passengers.isEmpty()) { "Assembly $id has passenger relationships; journal retained" }
                check(restoreJournalBlocks(id, record, level)) { "Assembly recovery for $id is obstructed; journal retained" }
                pendingConversions.remove(id)
                entityUnloaded(entity, level)
                entity.discard()
                checkpointConversion(id, level)
            } finally {
                newlyForced.forEach { level.setChunkForced(it.x, it.z, false) }
            }
        }
    }

    private fun restoreJournalBlocks(id: UUID, record: AssemblyRecoveryRecord, level: ServerLevel): Boolean {
        val blocks = record.definition.blocks.map {
            ConvertedBlock(level, record.origin.offset(it.offset), it.state)
        }.toMutableList()
        if (record.fenced) {
            if (blocks.any { block -> level.getBlockState(block.position).let { !it.isAir && it != block.state } }) return false
            blocks.removeIf { level.getBlockState(it.position) == it.state }
        }
        return restoreBlocks(blocks, id)
    }

    fun usePhysicsGunSecondary(player: Player, prop: PhysicsPropEntity, changeToBlock: Boolean): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (isTerrainPaused(prop, level)) return false
        if (prop.level() !== level || prop.runtimeBodyId == null || player.distanceToSqr(prop) > PHYSICS_GUN_REACH_SQUARED) {
            return false
        }
        if (changeToBlock) {
            if (prop.assembly != null) return false
            val position = BlockPos.containing(prop.x, prop.y, prop.z)
            val actor = player as? ServerPlayer ?: return false
            if (!actor.mayBuild() || !actor.mayInteract(level, position) || !level.worldBorder.isWithinBounds(position) ||
                !level.getBlockState(position).canBeReplaced() ||
                !level.setBlock(position, prop.blockState, Block.UPDATE_ALL)
            ) return false
            entityUnloaded(prop, level)
            prop.discard()
            return true
        }
        if (prop.frozen) return true
        releaseProp(prop.uuid)
        val bodyId = prop.runtimeBodyId ?: return false
        if (!service.submit(PhysicsCommand.SetMotionType(level.physicsDimensionId(), bodyId, PhysicsMotionType.STATIC))) return false
        prop.frozen = true
        prop.setDeltaMovement(Vec3.ZERO)
        prop.angularVelocity = PhysicsVector.ZERO
        return true
    }

    fun useToolGunWeld(player: Player, prop: PhysicsPropEntity): ToolGunWeldResult {
        val level = player.level() as? ServerLevel ?: return ToolGunWeldResult.FAILED
        if (isTerrainPaused(prop, level)) return ToolGunWeldResult.FAILED
        val bodyId = prop.runtimeBodyId
        if (prop.level() !== level || bodyId == null || player.distanceToSqr(prop) > TOOL_GUN_REACH_SQUARED ||
            !player.isHolding { it.item === TumbleItems.TOOL_GUN }
        ) return ToolGunWeldResult.FAILED
        val selected = weldSelectionByPlayer[player.uuid]
        if (selected == null) {
            weldSelectionByPlayer[player.uuid] = SelectedWeld(prop.uuid, ToolGunFace.selectedFace(player, prop))
            sendToolGunState(player)
            return ToolGunWeldResult.SELECTED
        }
        weldSelectionByPlayer.remove(player.uuid)
        if (selected.propId == prop.uuid) {
            sendToolGunState(player)
            return ToolGunWeldResult.CANCELLED
        }
        val first = props[selected.propId]
        val firstBody = first?.runtimeBodyId
        val key = WeldKey.of(selected.propId, prop.uuid)
        if (first == null || firstBody == null || first.level() !== level || isTerrainPaused(first, level) || key in activeWelds) {
            sendToolGunState(player)
            return ToolGunWeldResult.FAILED
        }
        val requestId = UUID.randomUUID()
        return if (service.submit(PhysicsCommand.CreateWeld(level.physicsDimensionId(), firstBody, bodyId, requestId))) {
            pendingConnections[requestId] = PendingConnection(key, true, player.uuid)
            activeWelds += key
            weldHistoryByPlayer.getOrPut(player.uuid) { java.util.ArrayDeque() }.addLast(key)
            sendToolGunState(player)
            ToolGunWeldResult.WELDED
        } else {
            sendToolGunState(player)
            ToolGunWeldResult.FAILED
        }
    }

    fun cycleToolGunMode(player: Player, delta: Int) {
        if (delta != -1 && delta != 1 || !player.isHolding { it.item === TumbleItems.TOOL_GUN }) return
        toolModeByPlayer[player.uuid] = toolGunMode(player).cycle(delta)
        weldSelectionByPlayer.remove(player.uuid)
        sendToolGunState(player)
    }

    fun toolGunMode(player: Player) = toolModeByPlayer[player.uuid] ?: ToolGunMode.WELD

    fun useToolGun(player: Player, prop: PhysicsPropEntity): ToolGunResult {
        val mode = toolGunMode(player)
        if (mode == ToolGunMode.WELD) {
            return when (useToolGunWeld(player, prop)) {
                ToolGunWeldResult.SELECTED -> ToolGunResult.SELECTED
                ToolGunWeldResult.WELDED -> ToolGunResult.APPLIED
                ToolGunWeldResult.CANCELLED -> ToolGunResult.CANCELLED
                ToolGunWeldResult.FAILED -> ToolGunResult.FAILED
            }
        }
        val level = player.level() as? ServerLevel ?: return ToolGunResult.FAILED
        if (isTerrainPaused(prop, level)) return ToolGunResult.FAILED
        val bodyId = prop.runtimeBodyId
        if (prop.level() !== level || bodyId == null || player.distanceToSqr(prop) > TOOL_GUN_REACH_SQUARED ||
            !player.isHolding { it.item === TumbleItems.TOOL_GUN }
        ) return ToolGunResult.FAILED

        if (mode == ToolGunMode.REMOVER) {
            entityUnloaded(prop, level)
            prop.discard()
            return ToolGunResult.APPLIED
        }
        if (mode == ToolGunMode.WEIGHT_BUOYANCY) {
            prop.toolPhysicsPreset = prop.toolPhysicsPreset.next()
            return if (recreateBody(prop, level)) {
                ToolGunResult.APPLIED
            } else ToolGunResult.FAILED
        }

        val selected = weldSelectionByPlayer[player.uuid]
        if (selected == null) {
            weldSelectionByPlayer[player.uuid] = SelectedWeld(prop.uuid, ToolGunFace.selectedFace(player, prop))
            sendToolGunState(player)
            return ToolGunResult.SELECTED
        }
        weldSelectionByPlayer.remove(player.uuid)
        if (selected.propId == prop.uuid) {
            sendToolGunState(player)
            return ToolGunResult.CANCELLED
        }
        val first = props[selected.propId]
        val firstBody = first?.runtimeBodyId
        if (first == null || firstBody == null || first.level() !== level || isTerrainPaused(first, level)) {
            sendToolGunState(player)
            return ToolGunResult.FAILED
        }
        val dimension = level.physicsDimensionId()
        val requestId = UUID.randomUUID()
        val accepted = when (mode) {
            ToolGunMode.ROPE -> {
                val length = first.position().distanceTo(prop.position()).toFloat()
                length.isFinite() && length > 0f &&
                    service.submit(PhysicsCommand.CreateRope(dimension, firstBody, bodyId, length, requestId))
            }
            ToolGunMode.AXIS -> service.submit(PhysicsCommand.CreateAxis(dimension, firstBody, bodyId, requestId))
            ToolGunMode.NO_COLLIDE -> service.submit(PhysicsCommand.CreateNoCollide(dimension, firstBody, bodyId, requestId))
        }
        if (accepted) {
            val key = WeldKey.of(first.uuid, prop.uuid)
            pendingConnections[requestId] = PendingConnection(key, false, player.uuid)
            toolConnections += key
        }
        sendToolGunState(player)
        return if (accepted) ToolGunResult.APPLIED else ToolGunResult.FAILED
    }

    fun useToolGunPrimary(player: Player, prop: PhysicsPropEntity): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        if (isTerrainPaused(prop, level)) return false
        val bodyId = prop.runtimeBodyId ?: return false
        if (prop.level() !== level || player.distanceToSqr(prop) > TOOL_GUN_REACH_SQUARED) return false
        return when (toolGunMode(player)) {
            ToolGunMode.WELD -> removeToolGunWelds(player, prop)
            ToolGunMode.ROPE, ToolGunMode.AXIS, ToolGunMode.NO_COLLIDE -> {
                weldSelectionByPlayer.remove(player.uuid)
                service.submit(PhysicsCommand.DestroyToolConstraints(level.physicsDimensionId(), bodyId))
                    .also { accepted ->
                        if (accepted) {
                            toolConnections.removeIf { it.contains(prop.uuid) }
                            confirmedToolConnections.removeIf { it.contains(prop.uuid) }
                            pendingConnections.entries.removeIf { !it.value.weld && it.value.key.contains(prop.uuid) }
                        }
                        sendToolGunState(player)
                    }
            }
            ToolGunMode.WEIGHT_BUOYANCY -> {
                prop.toolPhysicsPreset = ToolPhysicsPreset.DEFAULT
                recreateBody(prop, level)
            }
            ToolGunMode.REMOVER -> {
                entityUnloaded(prop, level)
                prop.discard()
                true
            }
        }
    }

    private fun recreateBody(prop: PhysicsPropEntity, level: ServerLevel): Boolean {
        val bodyId = prop.runtimeBodyId ?: return false
        releaseProp(prop.uuid)
        val dimension = level.physicsDimensionId()
        if (!service.submit(PhysicsCommand.DestroyBody(dimension, bodyId))) return false
        toolConnections.removeIf { it.contains(prop.uuid) }
        confirmedToolConnections.removeIf { it.contains(prop.uuid) }
        pendingConnections.entries.removeIf { it.value.key.contains(prop.uuid) }
        val removedWelds = activeWelds.filter { it.contains(prop.uuid) }.toSet()
        activeWelds.removeAll(removedWelds)
        weldHistoryByPlayer.values.forEach { history -> history.removeIf(removedWelds::contains) }
        submersionByProp.remove(prop.uuid)
        associations.remove(prop.uuid)
        bodyOwners.remove(RuntimeBodyKey(dimension, bodyId))
        prop.runtimeBodyId = null
        prop.physicsPending = false
        associations.register(prop.uuid)
        queueBodyCreation(prop, level)
        return prop.physicsPending
    }

    fun removeToolGunWelds(player: Player, prop: PhysicsPropEntity): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val bodyId = prop.runtimeBodyId ?: return false
        if (prop.level() !== level || player.distanceToSqr(prop) > TOOL_GUN_REACH_SQUARED ||
            !player.isHolding { it.item === TumbleItems.TOOL_GUN }
        ) return false
        weldSelectionByPlayer.remove(player.uuid)
        val removed = activeWelds.filter { it.contains(prop.uuid) }
        if (removed.isEmpty()) {
            sendToolGunState(player)
            return false
        }
        val accepted = service.submit(PhysicsCommand.DestroyWelds(level.physicsDimensionId(), bodyId))
        if (accepted) {
            activeWelds.removeAll(removed.toSet())
            pendingConnections.entries.removeIf { it.value.weld && it.value.key in removed }
            weldHistoryByPlayer.values.forEach { history -> history.removeIf(removed::contains) }
        }
        sendToolGunState(player)
        return accepted
    }

    fun undoToolGunWeld(player: Player): Boolean {
        val level = player.level() as? ServerLevel ?: return false
        val history = weldHistoryByPlayer[player.uuid] ?: return false
        while (history.isNotEmpty()) {
            val key = history.last()
            if (key !in activeWelds) {
                history.removeLast()
                continue
            }
            val firstProp = props[key.first]
            val secondProp = props[key.second]
            if (firstProp != null && isTerrainPaused(firstProp, level) ||
                secondProp != null && isTerrainPaused(secondProp, level)
            ) return false
            val first = firstProp?.runtimeBodyId
            val second = secondProp?.runtimeBodyId
            val accepted = first != null && second != null &&
                service.submit(PhysicsCommand.DestroyWeld(level.physicsDimensionId(), first, second))
            if (accepted) {
                history.removeLast()
                activeWelds.remove(key)
                pendingConnections.entries.removeIf { it.value.weld && it.value.key == key }
            }
            sendToolGunState(player)
            return accepted
        }
        sendToolGunState(player)
        return false
    }

    private fun sendToolGunState(player: Player) {
        val serverPlayer = player as? ServerPlayer ?: return
        if (serverPlayer.connection == null) return
        if (!ServerPlayNetworking.canSend(serverPlayer, ToolGunStatePayload.TYPE)) return
        val selected = weldSelectionByPlayer[player.uuid]
        val canUndo = weldHistoryByPlayer[player.uuid]?.any(activeWelds::contains) == true
        ServerPlayNetworking.send(
            serverPlayer,
            ToolGunStatePayload(toolGunMode(player), selected?.propId, selected?.face, canUndo)
        )
    }

    fun entityLoaded(entity: Entity, level: ServerLevel) {
        if (entity !is PhysicsPropEntity || props.putIfAbsent(entity.uuid, entity) != null) return
        unloadedTerrainWaits.filter { it.level === level && it.propId == entity.uuid }.forEach { it.observedUnload = true }
        if (entity.assembly != null) assemblies[entity.uuid] = entity
        associations.register(entity.uuid)
        recoveryRecords[entity.uuid]?.let { entity.recoveryOrigin = it.origin }
        val recoveryOrigin = entity.recoveryOrigin
        val recoveryStructure = entity.assembly
        if (recoveryOrigin != null && recoveryStructure != null) {
            pendingConversions[entity.uuid] = recoveryStructure.definition.blocks.map {
                ConvertedBlock(level, recoveryOrigin.offset(it.offset), it.state)
            }
            failedAssemblyConversions += entity.uuid
            return
        }
        activateEntity(entity, level)
    }

    private fun activateEntity(entity: PhysicsPropEntity, level: ServerLevel) {
        if (entity.terrainBlockers.isNotEmpty()) {
            entity.physicsPending = true
            deferredTerrainProps.addLast(entity.uuid)
            return
        }
        val skipPrime = skipTerrainPrime.remove(entity.uuid)
        if (entity.assembly != null) {
            if (!service.isRunning()) return
            if (!terrain.primeForAssembly(level, entity)) {
                if (!service.isRunning()) return
                entity.physicsPending = true
                deferredAssemblies.addLast(entity.uuid)
                return
            }
        } else if (!skipPrime) terrain.primeForProp(level, entity.blockPosition())
        queueBodyCreation(entity, level)
    }

    fun entityUnloaded(entity: Entity, level: ServerLevel, visibilityEvent: Boolean = false) {
        if (entity !is PhysicsPropEntity || props.remove(entity.uuid) == null) return
        assemblies.remove(entity.uuid)
        failedAssemblyConversions.remove(entity.uuid)
        val terrainWait = terrainWaits.remove(entity.uuid)
        deferredAssemblies.remove(entity.uuid)
        deferredTerrainProps.remove(entity.uuid)
        val reason = entity.removalReason
        if (visibilityEvent && reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED &&
            reason != Entity.RemovalReason.CHANGED_DIMENSION && entity.assembly != null
        ) markConnectedTerrainWait(entity.uuid, terrain.coveredChunks(entity))
        if (visibilityEvent && reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            pendingConversions.remove(entity.uuid)?.let { conversions ->
                if (entity.recoveryOrigin == null) conversions.forEach(::notifyConvertedBlock)
            }
            pendingItemConversions.remove(entity.uuid)
        } else {
            rollbackPendingConversion(entity.uuid)
        }
        pendingPhysicsGunHolds.remove(entity.uuid)
        skipTerrainPrime.remove(entity.uuid)
        submersionByProp.remove(entity.uuid)
        releaseProp(entity.uuid)
        launchedProps.remove(entity.uuid)
        weldSelectionByPlayer.entries.removeIf { it.value.propId == entity.uuid }
        val removedWelds = activeWelds.filter { it.contains(entity.uuid) }
        activeWelds.removeAll(removedWelds.toSet())
        toolConnections.removeIf { it.contains(entity.uuid) }
        confirmedToolConnections.removeIf { it.contains(entity.uuid) }
        pendingConnections.entries.removeIf { it.value.key.contains(entity.uuid) }
        weldHistoryByPlayer.values.forEach { history -> history.removeIf(removedWelds::contains) }
        val bodyId = associations.remove(entity.uuid) ?: entity.runtimeBodyId
        if (bodyId != null) {
            val dimension = level.physicsDimensionId()
            val isVisibilityUnload = visibilityEvent && reason != Entity.RemovalReason.KILLED &&
                reason != Entity.RemovalReason.DISCARDED && reason != Entity.RemovalReason.CHANGED_DIMENSION &&
                entity.assembly != null
            if (terrainWait != null || isVisibilityUnload && service.submit(PhysicsCommand.SuspendBody(dimension, bodyId))) {
                unloadedTerrainWaits += UnloadedTerrainWait(
                    level, entity.uuid, bodyId, terrainWait ?: mutableSetOf(), terrain.coveredChunks(entity),
                    terrainWait != null, service.latestFrame(dimension).physicsStep
                )
            }
            bodyOwners.remove(RuntimeBodyKey(dimension, bodyId))
            service.submit(PhysicsCommand.DestroyBody(dimension, bodyId))
        }
        entity.runtimeBodyId = null
        entity.physicsPending = false
    }

    fun chunkLoaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val chunk = ChunkPos.pack(chunkX, chunkZ)
        unavailableTerrain[level]?.let {
            it.remove(chunk)
            if (it.isEmpty()) unavailableTerrain.remove(level)
        }
        assemblies.values.filter { it.level() === level }.forEach { terrainWaits[it.uuid]?.remove(chunk) }
        unloadedTerrainWaits.filter { it.level === level }.forEach { it.chunks.remove(chunk) }
        val needed = props.values.any { prop ->
            prop.level() === level &&
                kotlin.math.abs(Math.floorDiv(prop.blockX, 16) - chunkX) <= 1 &&
                kotlin.math.abs(Math.floorDiv(prop.blockZ, 16) - chunkZ) <= 1
        } || assemblies.values.any { it.level() === level && chunk in terrain.coveredChunks(it) }
        if (needed) terrain.chunkLoaded(level, chunkX, chunkZ)
    }

    fun chunkUnloaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val chunk = ChunkPos.pack(chunkX, chunkZ)
        val affected = assemblies.values.filter { it.level() === level && chunk in terrain.coveredChunks(it) }
        val unloaded = unloadedTerrainWaits.filter { it.level === level && chunk in it.watchedChunks }
        if (affected.isNotEmpty() || unloaded.isNotEmpty()) unavailableTerrain.getOrPut(level, ::mutableSetOf).add(chunk)
        unloaded.forEach {
            it.chunks.add(chunk)
            it.observedUnload = true
        }
        affected.forEach {
            pauseForTerrain(it, level, setOf(chunk))
        }
        terrain.chunkUnloaded(level, chunkX, chunkZ)
    }

    private fun pauseForTerrain(prop: PhysicsPropEntity, level: ServerLevel, chunks: Set<Long>) {
        val body = prop.runtimeBodyId
        if (body == null && (!prop.physicsPending || prop.uuid in deferredAssemblies)) return
        val waiting = terrainWaits[prop.uuid]
        if (waiting != null) {
            waiting.addAll(chunks)
            markConnectedTerrainWait(prop.uuid, chunks)
        } else if (service.submit(if (body == null) PhysicsCommand.SuspendProp(level.physicsDimensionId(), prop.uuid)
            else PhysicsCommand.SuspendBody(level.physicsDimensionId(), body))) {
            terrainWaits[prop.uuid] = chunks.toMutableSet()
            markConnectedTerrainWait(prop.uuid, chunks)
            releaseProp(prop.uuid)
        }
    }

    private fun markConnectedTerrainWait(root: UUID, chunks: Set<Long>) {
        val adjacency = mutableMapOf<UUID, MutableList<UUID>>()
        (activeWelds + toolConnections).forEach { edge ->
            adjacency.getOrPut(edge.first, ::mutableListOf).add(edge.second)
            adjacency.getOrPut(edge.second, ::mutableListOf).add(edge.first)
        }
        val visited = mutableSetOf(root)
        val queue = ArrayDeque<UUID>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            props[id]?.let { it.terrainBlockers = (it.terrainBlockers + chunks).distinct() }
            adjacency[id].orEmpty().forEach { if (visited.add(it)) queue.addLast(it) }
        }
    }

    internal fun isTerrainPaused(prop: PhysicsPropEntity, level: ServerLevel): Boolean {
        if (prop.uuid in terrainWaits || prop.terrainBlockers.isNotEmpty()) return true
        val body = prop.runtimeBodyId ?: return false
        return service.latestFrame(level.physicsDimensionId()).bodies[body]?.suspended == true
    }

    private fun terrainBlocked(level: ServerLevel, chunk: Long): Boolean =
        chunk in unavailableTerrain[level].orEmpty() || !level.chunkSource.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)) ||
            unloadedTerrainWaits.any { it.level === level && !it.observedUnload && chunk in it.watchedChunks }

    fun blocksPlaced(level: ServerLevel, positions: Collection<BlockPos>) {
        if (!level.gameRules.get(TumbleGameRules.CONVERT_PLACED_BLOCKS)) return
        positions.take(maxOf(0, MAX_PENDING_CONVERSIONS - pendingPlacedBlocks.size)).forEach { position ->
            pendingPlacedBlocks += PendingPlacedBlock(level, position.immutable())
        }
    }

    fun itemLoaded(item: ItemEntity, level: ServerLevel) {
        if (ignoredDroppedItems.remove(item.uuid) ||
            !level.gameRules.get(TumbleGameRules.CONVERT_DROPPED_BLOCKS) ||
            pendingDroppedItems.size >= MAX_PENDING_CONVERSIONS
        ) return
        pendingDroppedItems[item.uuid] = level to item
    }

    fun convertExplodedBlocks(
        level: ServerLevel,
        origin: PhysicsVector,
        positions: List<BlockPos>
    ): List<BlockPos> {
        if (!level.gameRules.get(TumbleGameRules.CONVERT_EXPLODED_BLOCKS)) return positions
        val converted = convertBlocks(level, positions.map { position ->
            val center = PhysicsVector(position.x + 0.5, position.y + 0.5, position.z + 0.5)
            BlockConversion(
                position.immutable(),
                ExplosionMotion.velocity(
                    origin,
                    center,
                    tuning.get(TumbleTuningKey.EXPLOSION_RADIUS),
                    tuning.get(TumbleTuningKey.EXPLOSION_SPEED)
                ) ?: PhysicsVector.ZERO
            )
        }).keys
        return if (converted.isEmpty()) positions else positions.filterNot(converted::contains)
    }

    fun pistonMoved(level: ServerLevel, boxes: List<PhysicsBox>, movement: PhysicsVector) {
        if (boxes.isEmpty() || boxes.size > MAX_PISTON_BOXES) return
        submitPistonMotion(PistonMotion(level, boxes.toList(), movement))
    }

    fun clear(level: ServerLevel): Int {
        val removed = props.values.filter { it.level() === level && it.uuid !in failedAssemblyConversions && !(it.assembly != null && it.physicsPending) }
        removed.forEach { entity ->
            entityUnloaded(entity, level)
            entity.discard()
        }
        return removed.size
    }

    private fun convertPendingPlacedBlocks() {
        val batch = ArrayList<PendingPlacedBlock>(MAX_CONVERSIONS_PER_TICK)
        val iterator = pendingPlacedBlocks.iterator()
        repeat(minOf(MAX_CONVERSIONS_PER_TICK, pendingPlacedBlocks.size)) {
            batch += iterator.next()
            iterator.remove()
        }
        batch.groupBy(PendingPlacedBlock::level).forEach { (level, blocks) ->
            if (level.gameRules.get(TumbleGameRules.CONVERT_PLACED_BLOCKS)) {
                convertBlocks(level, blocks.map { BlockConversion(it.position, PhysicsVector.ZERO) })
            }
        }
    }

    private fun convertPendingDroppedItems() {
        val batch = ArrayList<Pair<ServerLevel, ItemEntity>>(MAX_CONVERSIONS_PER_TICK)
        val iterator = pendingDroppedItems.iterator()
        repeat(minOf(MAX_CONVERSIONS_PER_TICK, pendingDroppedItems.size)) {
            batch += iterator.next().value
            iterator.remove()
        }
        batch.groupBy { it.first }.forEach { (level, entries) ->
            if (!level.gameRules.get(TumbleGameRules.CONVERT_DROPPED_BLOCKS)) return@forEach
            val drops = entries.mapNotNull { (_, item) ->
                if (!item.isAlive || !isRecentPhysicalDrop(item.age)) return@mapNotNull null
                val stack = item.item
                val block = (stack.item as? BlockItem)?.block ?: return@mapNotNull null
                val state = block.defaultBlockState()
                if (state.hasBlockEntity() || MinecraftTerrainAdapter.dynamicShape(
                        state.getCollisionShape(level, item.blockPosition()).toAabbs()
                    ) == null
                ) return@mapNotNull null
                DroppedBlock(item, stack.copy(), state)
            }.take(maxOf(0, tuning.get(TumbleTuningKey.MAX_PHYSICS_OBJECTS).toInt() - props.size))
            terrain.primeForPositions(level, drops.map { it.item.blockPosition() })
            drops.forEach { drop ->
                val velocity = drop.item.deltaMovement
                val prop = try {
                    spawn(
                        level,
                        PhysicsVector(drop.item.x, drop.item.y, drop.item.z),
                        drop.state,
                        PhysicsVector(velocity.x, velocity.y, velocity.z),
                        primeTerrain = false
                    )
                } catch (error: Throwable) {
                    logger.error("Failed to convert dropped block item {}", drop.item.uuid, error)
                    null
                } ?: return@forEach
                if (!prop.physicsPending && prop.runtimeBodyId == null) {
                    prop.discard()
                    return@forEach
                }
                pendingItemConversions[prop.uuid] = ConvertedItem(
                    level,
                    PhysicsVector(drop.item.x, drop.item.y, drop.item.z),
                    drop.stack.copyWithCount(1),
                    PhysicsVector(velocity.x, velocity.y, velocity.z)
                )
                if (drop.stack.count == 1) {
                    drop.item.discard()
                } else {
                    drop.item.item = drop.stack.copyWithCount(drop.stack.count - 1)
                }
            }
        }
    }

    private fun convertBlocks(
        level: ServerLevel,
        requests: Collection<BlockConversion>
    ): Map<BlockPos, PhysicsPropEntity> {
        val candidates = linkedMapOf<BlockPos, Pair<BlockState, PhysicsVector>>()
        requests.distinctBy(BlockConversion::position)
            .take(maxOf(0, tuning.get(TumbleTuningKey.MAX_PHYSICS_OBJECTS).toInt() - props.size))
            .forEach { request ->
            val position = request.position
            if (!level.chunkSource.hasChunk(position.x shr 4, position.z shr 4)) return@forEach
            val state = level.getBlockState(position)
            if (state.isAir || state.hasBlockEntity() || state.getDestroySpeed(level, position) < 0f ||
                MinecraftTerrainAdapter.dynamicShape(state.getCollisionShape(level, position).toAabbs()) == null ||
                !level.setBlock(position, Blocks.AIR.defaultBlockState(), CONVERSION_FLAGS)
            ) return@forEach
            candidates[position] = state to request.linearVelocity
        }
        if (candidates.isEmpty()) return emptyMap()

        terrain.primeForPositions(level, candidates.keys)
        val converted = linkedMapOf<BlockPos, PhysicsPropEntity>()
        candidates.forEach { (position, captured) ->
            val (state, velocity) = captured
            val prop = try {
                spawn(
                    level,
                    PhysicsVector(position.x + 0.5, position.y + 0.5, position.z + 0.5),
                    state,
                    velocity,
                    angularVelocity = PhysicsVector.ZERO,
                    primeTerrain = false
                )
            } catch (error: Throwable) {
                logger.error("Failed to convert block at {}", position, error)
                null
            }
            if (prop == null || !prop.physicsPending && prop.runtimeBodyId == null) {
                prop?.discard()
                restoreBlock(ConvertedBlock(level, position, state))
                return@forEach
            }
            pendingConversions[prop.uuid] = listOf(ConvertedBlock(level, position, state))
            converted[position] = prop
        }
        return converted
    }

    fun tick() {
        managerTick++
        drainAcknowledgements()
        drainImpactEvents()
        repeat(minOf(MAX_DEFERRED_ASSEMBLIES_PER_TICK, failedAssemblyConversions.size)) {
            val id = failedAssemblyConversions.first()
            failedAssemblyConversions.remove(id)
            val entity = props[id]
            if (entity != null && !entity.isRemoved && rollbackPendingConversion(id)) {
                entity.discard()
                checkpointConversion(id, entity.level() as ServerLevel)
            } else if (entity != null && !entity.isRemoved) failedAssemblyConversions += id
        }
        repeat(minOf(MAX_DEFERRED_ASSEMBLIES_PER_TICK, deferredTerrainProps.size)) {
            val id = deferredTerrainProps.removeFirst()
            val entity = props[id] ?: return@repeat
            val level = entity.level() as? ServerLevel ?: return@repeat
            val waiting = entity.terrainBlockers.any { terrainBlocked(level, it) }
            if (waiting || !service.isRunning() || !terrain.primeForAssembly(level, entity)) {
                deferredTerrainProps.addLast(id)
            } else {
                entity.terrainBlockers = emptyList()
                entity.physicsPending = false
                queueBodyCreation(entity, level)
            }
        }
        repeat(minOf(MAX_DEFERRED_ASSEMBLIES_PER_TICK, deferredAssemblies.size)) {
            val id = deferredAssemblies.removeFirst()
            val entity = assemblies[id] ?: return@repeat
            val level = entity.level() as? ServerLevel ?: return@repeat
            if (!service.isRunning()) {
                entity.physicsPending = false
                if (rollbackPendingConversion(id)) {
                    entity.discard()
                    checkpointConversion(id, level)
                }
                else retainFailedAssembly(entity)
                return@repeat
            }
            if (!terrain.primeForAssembly(level, entity)) {
                if (service.isRunning()) deferredAssemblies.addLast(id) else {
                    entity.physicsPending = false
                    if (rollbackPendingConversion(id)) {
                        entity.discard()
                        checkpointConversion(id, level)
                    }
                    else retainFailedAssembly(entity)
                }
            } else {
                entity.physicsPending = false
                queueBodyCreation(entity, level)
            }
        }
        convertPendingPlacedBlocks()
        convertPendingDroppedItems()
        unloadedTerrainWaits.removeIf { wait ->
            if (!wait.observedUnload || wait.chunks.isNotEmpty() || !service.isRunning()) return@removeIf false
            val frame = service.latestFrame(wait.level.physicsDimensionId())
            if (frame.physicsStep <= wait.unloadStep || wait.bodyId in frame.bodies) return@removeIf false
            val affected = props.values.filter { prop ->
                val body = prop.runtimeBodyId
                prop.level() === wait.level && body != null && wait.bodyId in (frame.bodies[body]?.suspensionRoots ?: emptySet())
            }
            val ready = affected.all { terrain.primeForAssembly(wait.level, it) }
            ready && service.submit(PhysicsCommand.ResumeBody(wait.level.physicsDimensionId(), wait.bodyId))
        }
        assemblies.values.forEach { prop ->
            val level = prop.level() as? ServerLevel ?: return@forEach
            val missing = terrain.coveredChunks(prop).filterTo(mutableSetOf()) {
                !level.chunkSource.hasChunk(ChunkPos.getX(it), ChunkPos.getZ(it))
            }
            if (missing.isNotEmpty()) pauseForTerrain(prop, level, missing)
            val waiting = terrainWaits[prop.uuid]
            val body = prop.runtimeBodyId
            if (waiting != null && waiting.isEmpty() && body != null && terrain.primeForAssembly(level, prop) &&
                service.submit(PhysicsCommand.ResumeBody(level.physicsDimensionId(), body))
            ) terrainWaits.remove(prop.uuid)
        }
        terrain.tick(server, props.values)
        actorContactsLastTick = pendingActorContacts
        actorImpulsesLastTick = 0
        val platformMotions = ArrayList<PlatformMotion>()
        props.values.toList().forEach { entity ->
            val bodyId = entity.runtimeBodyId ?: return@forEach
            val level = entity.level() as? ServerLevel ?: return@forEach
            val frame = service.latestFrame(level.physicsDimensionId())
            val snapshot = frame.bodies[bodyId] ?: return@forEach
            if (snapshot.suspended) releaseProp(entity.uuid)
            if (snapshot.suspended) {
                val chunks = snapshot.suspensionRoots.flatMapTo(mutableSetOf()) { root ->
                    val owner = bodyOwners[RuntimeBodyKey(level.physicsDimensionId(), root)]
                    val active = owner?.let { terrainWaits[it] }.orEmpty()
                    active + unloadedTerrainWaits.filter { it.level === level && it.bodyId == root }.flatMap { it.chunks }
                }
                entity.terrainBlockers = (entity.terrainBlockers.filter { terrainBlocked(level, it) } + chunks).distinct()
            } else entity.terrainBlockers = entity.terrainBlockers.filter { terrainBlocked(level, it) }
            val previousBox = entity.boundingBox
            val previousPosition = PhysicsVector(entity.x, entity.y, entity.z)
            entity.applySnapshot(frame.physicsStep, snapshot)
            val platformDelta = snapshot.transform.position - previousPosition
            if (MovingPlatformCarry.isSafeDelta(platformDelta)) {
                platformMotions += PlatformMotion(entity, level, previousBox, entity.boundingBox, platformDelta)
            }
            updateSubmersion(entity, level, bodyId, snapshot.sleeping)
            damageFromLaunch(entity, level, snapshot.transform.position)
        }
        val carriedActors = carryActors(platformMotions)
        pushActorsFromMovingProps(platformMotions, carriedActors)
        updateHeldProps()
        flushActorImpulses()
        flushExplosionVelocity()
    }

    fun registeredPropCount() = props.size
    internal fun findProp(id: UUID) = props[id]
    internal fun isRecoveringAssembly(id: UUID) = id in failedAssemblyConversions

    fun explosion(level: ServerLevel, origin: PhysicsVector) {
        if (!level.gameRules.get(TumbleGameRules.REACT_TO_EXPLOSIONS)) return
        props.values.forEach { prop ->
            if (prop.level() !== level || prop.runtimeBodyId == null) return@forEach
            val delta = ExplosionMotion.velocity(
                origin,
                PhysicsVector(prop.x, prop.y, prop.z),
                tuning.get(TumbleTuningKey.EXPLOSION_RADIUS),
                tuning.get(TumbleTuningKey.EXPLOSION_SPEED)
            ) ?: return@forEach
            pendingExplosionVelocity.merge(prop.uuid, delta, PhysicsVector::plus)
        }
    }

    fun clipActorMovement(actor: LivingEntity, movement: Vec3): Vec3 {
        if (carryingActors || !actor.isAlive || actor.isSpectator || movement.lengthSqr() <= 1e-12) return movement
        val level = actor.level() as? ServerLevel ?: return movement
        val actorBounds = PhysicsServices.actorCollisionBounds(actor)
        val broadphase = actorBounds.expandTowards(movement).inflate(BROADPHASE_MARGIN)
        val candidates = level.getEntitiesOfClass(PhysicsPropEntity::class.java, broadphase) { prop ->
            prop.actorCollidable && prop.runtimeBodyId != null
        }.toMutableList()
        assemblies.values.forEach { prop ->
            if (prop.level() === level && prop.actorCollidable && prop.runtimeBodyId != null &&
                prop.boundingBox.intersects(broadphase) && prop !in candidates
            ) candidates += prop
        }
        if (candidates.isEmpty()) return movement
        val shapes = candidates.flatMapIndexed { owner, prop ->
            val transform = PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z), prop.bodyRotation)
            prop.localCollisionBoxes.map { PropSweepShape(owner, transform, it) }
        }
        val requested = PhysicsVector(movement.x, movement.y, movement.z)
        val clipped = PropActorCollision.clipMovement(actorBounds.toPhysicsAabb(), requested, shapes)
        val mayRecordPlayerPush = actor !is ServerPlayer || lastPushRequestTick[actor.uuid] != managerTick
        var recordedPlayerPush = false
        clipped.contacts.groupBy(PropSweepContact::owner).forEach { (owner, contacts) ->
            val contact = contacts.maxBy { -requested.dot(it.normal) }
            if (mayRecordPlayerPush) {
                recordActorImpulse(candidates[owner], contact.normal, requested)
                recordedPlayerPush = true
            }
            constrainActorVelocity(actor, contact.normal)
        }
        if (recordedPlayerPush && actor is ServerPlayer) lastPushRequestTick[actor.uuid] = managerTick
        return Vec3(clipped.displacement.x, clipped.displacement.y, clipped.displacement.z)
    }

    fun requestActorPush(player: ServerPlayer, propId: UUID, movement: PhysicsVector) {
        if (!player.isAlive || player.isSpectator || movement.y != 0.0 ||
            lastPushRequestTick[player.uuid] == managerTick
        ) return
        val prop = props[propId] ?: return
        if (prop.level() !== player.level() || !prop.actorCollidable || prop.runtimeBodyId == null ||
            !prop.boundingBox.inflate(PUSH_REQUEST_REACH).intersects(PhysicsServices.actorCollisionBounds(player))
        ) return
        val transform = PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z), prop.bodyRotation)
        val shapes = prop.localCollisionBoxes.map { PropSweepShape(0, transform, it) }
        val contact = PropActorCollision.clipMovement(
            PhysicsServices.actorCollisionBounds(player).toPhysicsAabb(),
            movement,
            shapes
        ).contacts.maxByOrNull { -movement.dot(it.normal) } ?: return
        lastPushRequestTick[player.uuid] = managerTick
        recordActorImpulse(prop, contact.normal, movement)
    }

    private fun recordActorImpulse(prop: PhysicsPropEntity, normal: PhysicsVector, movement: PhysicsVector) {
        if (!transfersActorImpulse(normal)) return
        val speedIntoProp = maxOf(0.0, -movement.dot(normal) * TICKS_PER_SECOND)
        if (speedIntoProp <= 1e-4) return
        val pushDirection = normal * -1.0
        val currentPushSpeed = PhysicsVector(
            prop.deltaMovement.x,
            prop.deltaMovement.y,
            prop.deltaMovement.z
        ).dot(pushDirection)
        val magnitude = actorPushImpulse(speedIntoProp, currentPushSpeed, prop.mass.toDouble())
        if (magnitude <= 1e-6) return
        pendingActorImpulses.merge(prop.uuid, normal * -magnitude, PhysicsVector::plus)
        pendingActorContacts++
    }

    private fun constrainActorVelocity(actor: LivingEntity, normal: PhysicsVector) {
        val velocity = actor.deltaMovement
        val intoSurface = velocity.x * normal.x + velocity.y * normal.y + velocity.z * normal.z
        if (intoSurface < 0.0) actor.setDeltaMovement(
            velocity.x - normal.x * intoSurface,
            velocity.y - normal.y * intoSurface,
            velocity.z - normal.z * intoSurface
        )
        if (normal.y > 0.5) {
            actor.fallDistance = 0.0
            actor.setOnGround(true)
        }
    }

    private fun resolveActorAgainstProp(
        actor: LivingEntity,
        prop: PhysicsPropEntity,
        movement: Vec3
    ): PropActorContact? {
        val transform = PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z), prop.bodyRotation)
        val skin = PhysicsVector(CONTACT_SKIN, CONTACT_SKIN, CONTACT_SKIN)
        val boxes = prop.localCollisionBoxes.map { PhysicsBox(it.min - skin, it.max + skin) }
        var impulseContact: PropActorContact? = null
        repeat(MAX_SHAPE_CONTACT_PASSES) {
            val actorBounds = PhysicsServices.actorCollisionBounds(actor).toPhysicsAabb()
            val contact = boxes.asSequence().mapNotNull { PropActorCollision.contact(transform, it, actorBounds) }
                .minByOrNull { it.translation.squaredLength() } ?: return impulseContact
            val previous = impulseContact
            val speed = -(movement.x * contact.normal.x + movement.y * contact.normal.y + movement.z * contact.normal.z)
            val previousSpeed = previous?.let {
                -(movement.x * it.normal.x + movement.y * it.normal.y + movement.z * it.normal.z)
            } ?: Double.NEGATIVE_INFINITY
            if (speed > previousSpeed) impulseContact = contact
            resolveActorContact(actor, contact)
        }
        return impulseContact
    }

    private fun resolveActorContact(actor: LivingEntity, contact: PropActorContact) {
        val correction = contact.translation
        carryingActors = true
        try {
            actor.move(MoverType.SHULKER, Vec3(correction.x, correction.y, correction.z))
        } finally {
            carryingActors = false
        }
        constrainActorVelocity(actor, contact.normal)
    }

    private fun carryActors(motions: List<PlatformMotion>): Set<UUID> {
        if (motions.isEmpty()) return emptySet()
        val carried = mutableSetOf<UUID>()
        var remaining = MAX_PLATFORM_ACTORS_PER_TICK
        motions.forEach { motion ->
            if (remaining <= 0) return@forEach
            val search = motion.previous.minmax(motion.current).inflate(PLATFORM_SEARCH_MARGIN)
            motion.level.getEntitiesOfClass(LivingEntity::class.java, search) { actor ->
                actor.isAlive && !actor.isSpectator && !actor.isPassenger && actor.deltaMovement.y <= JUMP_VELOCITY &&
                    (actor !is Player || !actor.abilities.flying)
            }.asSequence().filter { it.uuid !in carried }
                .take(minOf(MAX_ACTORS_PER_PLATFORM, remaining)).forEach { actor ->
                val delta = MovingPlatformCarry.displacement(
                    motion.previous.toPhysicsAabb(),
                    actor.boundingBox.toPhysicsAabb(),
                    motion.delta,
                    tuning.get(TumbleTuningKey.PLATFORM_GRACE)
                ) ?: return@forEach
                carried += actor.uuid
                remaining--
                actor.fallDistance = 0.0
                carryingActors = true
                try {
                    actor.move(MoverType.SHULKER, Vec3(delta.x, delta.y, delta.z))
                } finally {
                    carryingActors = false
                }
            }
        }
        return carried
    }

    private fun pushActorsFromMovingProps(motions: List<PlatformMotion>, carried: Set<UUID>) {
        var remaining = MAX_PLATFORM_ACTORS_PER_TICK
        motions.forEach { motion ->
            if (remaining <= 0) return@forEach
            motion.level.getEntitiesOfClass(LivingEntity::class.java, motion.current.inflate(CONTACT_SKIN)) { actor ->
                actor.uuid !in carried && actor.isAlive && !actor.isSpectator
            }.asSequence().take(minOf(MAX_ACTORS_PER_PLATFORM, remaining)).forEach { actor ->
                val relativeMovement = Vec3(-motion.delta.x, -motion.delta.y, -motion.delta.z)
                val contact = resolveActorAgainstProp(actor, motion.prop, relativeMovement) ?: return@forEach
                recordActorImpulse(
                    motion.prop,
                    contact.normal,
                    PhysicsVector(relativeMovement.x, relativeMovement.y, relativeMovement.z)
                )
                remaining--
            }
        }
    }

    private fun flushActorImpulses() {
        pendingActorImpulses.forEach { (propId, impulse) ->
            val prop = props[propId] ?: return@forEach
            val bodyId = prop.runtimeBodyId ?: return@forEach
            val level = prop.level() as? ServerLevel ?: return@forEach
            if (isTerrainPaused(prop, level)) return@forEach
            val length = impulse.length()
            if (length <= 1e-6) return@forEach
            val capped = if (length > MAX_TOTAL_IMPULSE) impulse * (MAX_TOTAL_IMPULSE / length) else impulse
            if (service.submit(PhysicsCommand.ApplyCentralImpulse(level.physicsDimensionId(), bodyId, capped))) {
                actorImpulsesLastTick++
            }
        }
        pendingActorImpulses.clear()
        pendingActorContacts = 0
    }

    private fun flushExplosionVelocity() {
        explosionPropsLastTick = 0
        pendingExplosionVelocity.forEach { (propId, velocity) ->
            val prop = props[propId] ?: return@forEach
            val bodyId = prop.runtimeBodyId ?: return@forEach
            val level = prop.level() as? ServerLevel ?: return@forEach
            if (isTerrainPaused(prop, level)) return@forEach
            val speed = velocity.length()
            val capped = if (speed > MAX_EXPLOSION_DELTA) velocity * (MAX_EXPLOSION_DELTA / speed) else velocity
            if (service.submit(PhysicsCommand.AddLinearVelocity(level.physicsDimensionId(), bodyId, capped))) {
                explosionPropsLastTick++
            }
        }
        pendingExplosionVelocity.clear()
    }

    private fun submitPistonMotion(motion: PistonMotion) {
        val origin = motion.boxes.first().center
        val localBoxes = motion.boxes.map { box -> PhysicsBox(box.min - origin, box.max - origin) }
        val shape = if (localBoxes.size == 1) PhysicsShapeDefinition.Box(localBoxes.single())
            else PhysicsShapeDefinition.CompoundBoxes(localBoxes)
        service.submit(
            PhysicsCommand.CreateTemporaryKinematicBody(
                motion.level.physicsDimensionId(),
                PhysicsBodyDefinition(
                    PhysicsTransform(origin - motion.movement),
                    shape = shape
                ),
                PhysicsTransform(origin),
                PISTON_TRAVEL_SECONDS,
                PISTON_LIFETIME_STEPS
            )
        )
    }

    private fun updateSubmersion(
        prop: PhysicsPropEntity,
        level: ServerLevel,
        bodyId: PhysicsBodyId,
        sleeping: Boolean
    ) {
        if (!MinecraftTerrainAdapter.hasWaterResponse(prop.blockState) ||
            sleeping && submersionByProp.containsKey(prop.uuid) && managerTick % WATER_IDLE_CHECK_TICKS != 0L
        ) return
        val fraction = MinecraftTerrainAdapter.submersion(level, prop.boundingBox)
        if (submersionByProp[prop.uuid] == fraction) return
        if (service.submit(PhysicsCommand.SetSubmersion(level.physicsDimensionId(), bodyId, fraction))) {
            submersionByProp[prop.uuid] = fraction
        }
    }

    private fun rollbackPendingConversion(propId: UUID): Boolean {
        val entity = props[propId]
        if (entity?.assembly != null && (entity.isPassenger || entity.passengers.isNotEmpty())) return false
        pendingConversions[propId]?.let {
            val remaining = it.toMutableList()
            if (recoveryRecords[propId]?.fenced == true) {
                if (remaining.any { block -> !block.level.isInWorldBounds(block.position) ||
                        !block.level.chunkSource.hasChunk(block.position.x shr 4, block.position.z shr 4) }) return false
                remaining.removeIf { block -> block.level.getBlockState(block.position) == block.state }
            }
            val restored = restoreBlocks(remaining, propId.takeIf { it in recoveryRecords })
            if (restored || props[propId]?.assembly == null) pendingConversions.remove(propId)
            else pendingConversions[propId] = remaining
            return restored
        }
        pendingItemConversions.remove(propId)?.let { return restoreItem(it) }
        return false
    }

    private fun retainFailedAssembly(entity: PhysicsPropEntity) {
        val origin = entity.recoveryOrigin ?: pendingConversions[entity.uuid]?.firstOrNull()?.position ?: return
        entity.recoveryOrigin = origin
        failedAssemblyConversions += entity.uuid
    }

    private fun restoreBlock(conversion: ConvertedBlock, owner: UUID? = null): Boolean =
        conversion.level.getBlockState(conversion.position).isAir &&
            if (owner == null) conversion.level.setBlock(conversion.position, conversion.state, CONVERSION_FLAGS)
            else setConversionBlock(conversion, conversion.state, owner)

    private fun restoreBlocks(conversions: MutableList<ConvertedBlock>, owner: UUID? = null): Boolean {
        if (conversions.any {
                !it.level.isInWorldBounds(it.position) ||
                    !it.level.chunkSource.hasChunk(it.position.x shr 4, it.position.z shr 4) ||
                    !it.level.getBlockState(it.position).isAir
            }) return false
        val iterator = conversions.iterator()
        while (iterator.hasNext()) {
            if (!restoreBlock(iterator.next(), owner)) return false
            iterator.remove()
        }
        return true
    }

    private fun notifyConvertedBlock(conversion: ConvertedBlock) {
        val level = conversion.level
        val position = conversion.position
        val current = level.getBlockState(position)
        level.updateNeighborsAt(position, conversion.state.block)
        conversion.state.updateIndirectNeighbourShapes(level, position, Block.UPDATE_CLIENTS)
        current.updateNeighbourShapes(level, position, Block.UPDATE_CLIENTS)
        current.updateIndirectNeighbourShapes(level, position, Block.UPDATE_CLIENTS)
    }

    private fun restoreItem(conversion: ConvertedItem): Boolean {
        val item = ItemEntity(
            conversion.level,
            conversion.position.x,
            conversion.position.y,
            conversion.position.z,
            conversion.stack.copy(),
            conversion.velocity.x,
            conversion.velocity.y,
            conversion.velocity.z
        )
        item.setDefaultPickUpDelay()
        ignoredDroppedItems += item.uuid
        if (conversion.level.addFreshEntity(item)) return true
        ignoredDroppedItems.remove(item.uuid)
        return false
    }

    fun toggleHeld(player: Player): Boolean {
        if (player.uuid in heldByPlayer || player.uuid in heldLivingByPlayer) {
            releasePlayer(player.uuid)
            return true
        }
        return when (val target = targetedGravityGunEntity(player)) {
            is PhysicsPropEntity -> hold(player, target, false)
            is LivingEntity -> holdLiving(player, target)
            else -> false
        }
    }

    fun togglePhysicsGunHeld(player: Player, prop: PhysicsPropEntity): Boolean {
        heldByPlayer[player.uuid]?.let {
            releasePlayer(player.uuid)
            return true
        }
        if (prop.physicsPending) {
            if (prop.uuid in pendingPhysicsGunHolds || player.uuid in pendingPhysicsGunHolds.values ||
                player.level() !== prop.level() || player.isSpectator ||
                !player.isHolding { it.item === TumbleItems.PHYSICS_GUN } ||
                player.distanceToSqr(prop) > PHYSICS_GUN_REACH_SQUARED
            ) return false
            pendingPhysicsGunHolds[prop.uuid] = player.uuid
            return true
        }
        return hold(player, prop, true)
    }

    private fun hold(player: Player, prop: PhysicsPropEntity, physicsGun: Boolean): Boolean {
        if (prop.uuid in heldByProp || prop.runtimeBodyId == null) return false
        val level = prop.level() as? ServerLevel ?: return false
        if (isTerrainPaused(prop, level)) return false
        val holdDistance = if (physicsGun) clampPhysicsGunDistance(player.eyePosition.distanceTo(prop.position())) else null
        if (prop.frozen) {
            if (!service.submit(
                    PhysicsCommand.SetMotionType(level.physicsDimensionId(), prop.runtimeBodyId!!, PhysicsMotionType.DYNAMIC)
                )
            ) return false
            prop.frozen = false
        }
        launchedProps.remove(prop.uuid)
        heldByPlayer[player.uuid] = prop.uuid
        heldByProp[prop.uuid] = player.uuid
        if (holdDistance != null) physicsGunHoldDistance[player.uuid] = holdDistance
        prop.actorCollidable = false
        prop.heldByEntityId = player.id
        return true
    }

    fun controlPhysicsGun(
        player: ServerPlayer,
        propId: UUID,
        distanceDelta: Float,
        yawDelta: Float,
        pitchDelta: Float
    ): Boolean {
        val prop = props[propId] ?: return false
        if (heldByPlayer[player.uuid] != propId || player.uuid !in physicsGunHoldDistance ||
            prop.level() !== player.level() || !player.isHolding { it.item === TumbleItems.PHYSICS_GUN } ||
            lastPhysicsGunControlTick[player.uuid] == managerTick
        ) return false
        lastPhysicsGunControlTick[player.uuid] = managerTick
        if (distanceDelta != 0f) {
            physicsGunHoldDistance[player.uuid] =
                clampPhysicsGunDistance((physicsGunHoldDistance[player.uuid] ?: MIN_PHYSICS_GUN_HOLD_DISTANCE) + distanceDelta * 0.5)
        }
        if (yawDelta == 0f && pitchDelta == 0f) return true
        val bodyId = prop.runtimeBodyId ?: return false
        val rotation = PhysicsGunRotation.apply(prop.bodyRotation, yawDelta.toDouble(), pitchDelta.toDouble(), player.yRot.toDouble())
        return service.submit(
            PhysicsCommand.SetTransform(
                (prop.level() as ServerLevel).physicsDimensionId(),
                bodyId,
                PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z), rotation)
            )
        )
    }

    private fun clampPhysicsGunDistance(distance: Double): Double {
        val maximum = tuning.get(TumbleTuningKey.GRAB_DISTANCE)
        return distance.coerceIn(minOf(MIN_PHYSICS_GUN_HOLD_DISTANCE, maximum), maximum)
    }

    fun launchHeld(player: Player): Boolean {
        if (!player.isHolding { it.item === TumbleItems.GRAVITY_GUN }) return false
        heldLivingByPlayer[player.uuid]?.let { target ->
            releaseLiving(player.uuid)
            if (!target.isAlive || target.isRemoved) return false
            val look = player.lookAngle
            target.setDeltaMovement(
                look.x * LIVING_ENTITY_LAUNCH_SPEED,
                look.y * LIVING_ENTITY_LAUNCH_SPEED,
                look.z * LIVING_ENTITY_LAUNCH_SPEED
            )
            target.syncVelocity = true
            return true
        }
        val propId = heldByPlayer[player.uuid] ?: return false
        val prop = props[propId]
        releasePlayer(player.uuid)
        val bodyId = prop?.runtimeBodyId ?: return false
        val level = prop.level() as? ServerLevel ?: return false
        val look = player.lookAngle
        val accepted = service.submit(
            PhysicsCommand.SetLinearVelocity(
                level.physicsDimensionId(),
                bodyId,
                PhysicsVector(
                    look.x * tuning.get(TumbleTuningKey.LAUNCH_SPEED),
                    look.y * tuning.get(TumbleTuningKey.LAUNCH_SPEED),
                    look.z * tuning.get(TumbleTuningKey.LAUNCH_SPEED)
                )
            )
        )
        if (accepted) {
            val hardness = prop.blockState.getDestroySpeed(level, prop.blockPosition())
            launchedProps[propId] = LaunchedProp(
                player.uuid,
                managerTick + tuning.get(TumbleTuningKey.LAUNCH_DAMAGE_TICKS).toLong(),
                PhysicsVector(prop.x, prop.y, prop.z),
                GravityGunDamage.damage(hardness, prop.blockState.`is`(Blocks.BEDROCK))
            )
        }
        return accepted
    }

    fun releasePlayer(playerId: UUID) {
        pendingPhysicsGunHolds.entries.removeIf { it.value == playerId }
        lastPushRequestTick.remove(playerId)
        lastPhysicsGunControlTick.remove(playerId)
        weldSelectionByPlayer.remove(playerId)
        weldHistoryByPlayer.remove(playerId)
        physicsGunHoldDistance.remove(playerId)
        releaseLiving(playerId)
        heldByPlayer.remove(playerId)?.let { propId ->
            heldByProp.remove(propId)
            props[propId]?.let { prop ->
                prop.actorCollidable = true
                prop.heldByEntityId = -1
            }
        }
    }

    fun releaseHeld(player: Player): Boolean {
        if (player.uuid !in heldByPlayer && player.uuid !in heldLivingByPlayer &&
            player.uuid !in pendingPhysicsGunHolds.values) return false
        releasePlayer(player.uuid)
        return true
    }

    fun heldPropCount() = heldByPlayer.size
    fun launchedPropCount() = launchedProps.size

    private fun releaseProp(propId: UUID) {
        heldByProp.remove(propId)?.let { playerId ->
            heldByPlayer.remove(playerId)
            physicsGunHoldDistance.remove(playerId)
            lastPhysicsGunControlTick.remove(playerId)
        }
        props[propId]?.let { prop ->
            prop.actorCollidable = true
            prop.heldByEntityId = -1
        }
    }

    private fun updateHeldProps() {
        heldByPlayer.entries.toList().forEach { (playerId, propId) ->
            val player = server.playerList.getPlayer(playerId)
            val prop = props[propId]
            if (player == null || prop == null || !player.isAlive || player.isSpectator ||
                player.level() !== prop.level() || !player.isHolding { TumbleItems.isGun(it.item) } ||
                player.distanceToSqr(prop) > maxOf(
                    16.0,
                    tuning.get(TumbleTuningKey.HOLD_DISTANCE) * 4.0
                ).let { it * it }) {
                releasePlayer(playerId)
                return@forEach
            }
            val bodyId = prop.runtimeBodyId ?: return@forEach
            val physicsGun = playerId in physicsGunHoldDistance
            val holdDistance = if (physicsGun) {
                physicsGunHoldDistance[playerId] ?: tuning.get(TumbleTuningKey.HOLD_DISTANCE)
            } else {
                tuning.get(TumbleTuningKey.GRAVITY_GUN_HOLD_DISTANCE)
            }
            val target = player.eyePosition.add(player.lookAngle.scale(holdDistance))
            val current = PhysicsVector(prop.x, prop.y, prop.z)
            val desired = PhysicsVector(target.x, target.y, target.z)
            val velocity = if (physicsGun) {
                GravityGunMotion.holdVelocity(current, desired)
            } else {
                GravityGunMotion.holdVelocity(
                    current,
                    desired,
                    prop.mass.toDouble(),
                    tuning.get(TumbleTuningKey.GRAVITY_GUN_FORCE)
                )
            }
            service.submit(PhysicsCommand.SetLinearVelocity((prop.level() as ServerLevel).physicsDimensionId(), bodyId, velocity))
        }
        heldLivingByPlayer.entries.toList().forEach { (playerId, entity) ->
            val player = server.playerList.getPlayer(playerId)
            if (player == null || !player.isAlive || player.isSpectator || !entity.isAlive || entity.isRemoved ||
                player.level() !== entity.level() || !player.isHolding { it.item === TumbleItems.GRAVITY_GUN } ||
                player.distanceToSqr(entity) > MAX_HELD_ENTITY_DISTANCE_SQUARED
            ) {
                releaseLiving(playerId)
                return@forEach
            }
            val target = player.eyePosition.add(
                player.lookAngle.scale(tuning.get(TumbleTuningKey.GRAVITY_GUN_HOLD_DISTANCE))
            )
            val velocity = GravityGunMotion.livingHoldVelocity(
                PhysicsVector(entity.x, entity.y + entity.bbHeight * 0.5, entity.z),
                PhysicsVector(target.x, target.y, target.z)
            )
            entity.setDeltaMovement(velocity.x, velocity.y, velocity.z)
            entity.fallDistance = 0.0
            entity.syncVelocity = true
        }
    }

    private fun holdLiving(player: Player, entity: LivingEntity): Boolean {
        if (entity === player || entity.uuid in heldLivingEntities || !entity.isAlive || entity.isSpectator) return false
        heldLivingByPlayer[player.uuid] = entity
        heldLivingEntities += entity.uuid
        entity.fallDistance = 0.0
        return true
    }

    private fun releaseLiving(playerId: UUID) {
        heldLivingByPlayer.remove(playerId)?.let { heldLivingEntities -= it.uuid }
    }

    private fun targetedGravityGunEntity(player: Player): Entity? {
        val start = player.eyePosition
        val grabDistance = tuning.get(TumbleTuningKey.GRAVITY_GUN_GRAB_DISTANCE)
        val end = start.add(player.lookAngle.scale(grabDistance))
        val blockDistance = player.pick(grabDistance, 0f, false).location.distanceToSqr(start)
        val search = AABB(
            minOf(start.x, end.x), minOf(start.y, end.y), minOf(start.z, end.z),
            maxOf(start.x, end.x), maxOf(start.y, end.y), maxOf(start.z, end.z)
        ).inflate(1.0)
        val candidates = ArrayList<Entity>()
        candidates += player.level().getEntitiesOfClass(PhysicsPropEntity::class.java, search) {
            it.runtimeBodyId != null && it.uuid !in heldByProp
        }
        candidates += player.level().getEntitiesOfClass(LivingEntity::class.java, search) {
            it !== player && it.isAlive && !it.isSpectator && it.uuid !in heldLivingEntities
        }
        return candidates.asSequence()
            .mapNotNull { entity ->
                entity.boundingBox.inflate(0.15).clip(start, end).orElse(null)
                    ?.let { entity to it.distanceToSqr(start) }
            }
            .filter { (_, distance) -> distance <= blockDistance + 1e-6 }
            .minByOrNull { it.second }
            ?.first
    }

    private fun damageFromLaunch(prop: PhysicsPropEntity, level: ServerLevel, current: PhysicsVector) {
        val launch = launchedProps[prop.uuid] ?: return
        if (managerTick > launch.expiresAt) {
            launchedProps.remove(prop.uuid)
            return
        }
        val previous = launch.lastPosition
        launch.lastPosition = current
        if ((current - previous).squaredLength() < 1e-6) return
        val search = AABB(
            minOf(previous.x, current.x) - 0.5,
            minOf(previous.y, current.y) - 0.5,
            minOf(previous.z, current.z) - 0.5,
            maxOf(previous.x, current.x) + 0.5,
            maxOf(previous.y, current.y) + 0.5,
            maxOf(previous.z, current.z) + 0.5
        )
        val victim = level.getEntitiesOfClass(LivingEntity::class.java, search) { entity ->
            entity.uuid != launch.shooter && entity.isAlive && !entity.isSpectator &&
                (entity is Enemy || entity is Player) && GravityGunDamage.sweptCubeHits(
                    previous,
                    current,
                    PhysicsAabb(
                        PhysicsVector(entity.boundingBox.minX, entity.boundingBox.minY, entity.boundingBox.minZ),
                        PhysicsVector(entity.boundingBox.maxX, entity.boundingBox.maxY, entity.boundingBox.maxZ)
                    )
                )
        }.firstOrNull() ?: return

        val shooter = server.playerList.getPlayer(launch.shooter)
        val source = shooter?.let { level.damageSources().thrown(prop, it) } ?: level.damageSources().fallingBlock(prop)
        victim.hurtServer(level, source, launch.damage)
        launchedProps.remove(prop.uuid)
    }

    private fun drainImpactEvents() {
        val detonations = linkedSetOf<UUID>()
        var consumed = 0
        while (consumed++ < MAX_IMPACTS_PER_TICK) {
            val notification = service.pollImpact() ?: break
            listOfNotNull(notification.event.bodyA, notification.event.bodyB).forEach { bodyId ->
                bodyOwners[RuntimeBodyKey(notification.dimension, bodyId)]?.let(detonations::add)
            }
        }
        detonations.forEach { propId ->
            val prop = props[propId] ?: return@forEach
            val level = prop.level() as? ServerLevel ?: return@forEach
            if (!prop.blockState.`is`(Blocks.TNT) ||
                !level.gameRules.get(TumbleGameRules.EXPLODE_TNT_ON_IMPACT)
            ) return@forEach
            val bodyId = prop.runtimeBodyId ?: return@forEach
            val position = service.latestFrame(level.physicsDimensionId()).bodies[bodyId]?.transform?.position
                ?: PhysicsVector(prop.x, prop.y, prop.z)
            entityUnloaded(prop, level)
            prop.discard()
            level.explode(
                null,
                position.x,
                position.y,
                position.z,
                tuning.get(TumbleTuningKey.TNT_EXPLOSION_POWER).toFloat(),
                false,
                Level.ExplosionInteraction.TNT
            )
        }
    }

    fun networkProps(): List<NetworkProp> = props.values.mapNotNull { entity ->
        val bodyId = entity.runtimeBodyId ?: return@mapNotNull null
        val level = entity.level() as? ServerLevel ?: return@mapNotNull null
        NetworkProp(entity.uuid, level.physicsDimensionId(), bodyId, bodyGenerations[entity.uuid] ?: return@mapNotNull null)
    }

    fun tuningValue(key: TumbleTuningKey) = tuning.get(key)

    fun setTuning(key: TumbleTuningKey, value: Double): Boolean {
        val previous = tuning.get(key)
        if (runCatching { tuning.set(key, value) }.isFailure) return false
        if (key !in NATIVE_TUNING_KEYS) return true
        val accepted = submitNativeTuning()
        if (!accepted) runCatching { tuning.set(key, previous) }
        return accepted
    }

    private fun submitNativeTuning() = service.submit(
            PhysicsCommand.ConfigureWorlds(
                tuning.get(TumbleTuningKey.GRAVITY),
                tuning.get(TumbleTuningKey.BUOYANCY).toFloat(),
                tuning.get(TumbleTuningKey.TNT_IMPACT_SPEED).toFloat()
            )
        )

    private fun queueBodyCreation(entity: PhysicsPropEntity, level: ServerLevel) {
        if (entity.physicsPending || entity.runtimeBodyId != null) return
        entity.physicsPending = true
        val velocity = entity.deltaMovement
        val blockPhysics = MinecraftTerrainAdapter.physicsData(entity.blockState)
        val assemblyShape = entity.assembly?.shape?.let { shape ->
            val scale = entity.toolPhysicsPreset.densityScale
            if (scale == 1f) shape else PhysicsShapeDefinition.MaterialBoxes(shape.boxes.map { part ->
                part.copy(material = part.material.copy(density = part.material.density * scale))
            })
        }
        val command = PhysicsCommand.SpawnBody(
                entity.uuid,
                level.physicsDimensionId(),
                PhysicsBodyDefinition(
                    transform = PhysicsTransform(
                        PhysicsVector(entity.x, entity.y, entity.z),
                        entity.bodyRotation
                    ),
                    shape = assemblyShape ?: MinecraftTerrainAdapter.dynamicShape(
                        entity.blockState.getCollisionShape(level, entity.blockPosition()).toAabbs()
                    ) ?: cc.modlabs.tumble.physics.PhysicsShapeDefinition.Box(
                        cc.modlabs.tumble.physics.PhysicsBox.UNIT
                    ),
                    material = blockPhysics.material.copy(
                        density = blockPhysics.material.density * entity.toolPhysicsPreset.densityScale
                    ),
                    motionType = if (entity.frozen) PhysicsMotionType.STATIC else PhysicsMotionType.DYNAMIC,
                    buoyancy = blockPhysics.buoyancy.copy(
                        enabled = blockPhysics.buoyancy.enabled || entity.toolPhysicsPreset.buoyancyScale > 1f,
                        forceScale = blockPhysics.buoyancy.forceScale * entity.toolPhysicsPreset.buoyancyScale
                    ),
                    linearVelocity = PhysicsVector(velocity.x, velocity.y, velocity.z),
                    angularVelocity = entity.angularVelocity,
                    emitImpactEvents = entity.blockState.`is`(Blocks.TNT)
                ),
                requestId = associations.requestId(entity.uuid)
            )
        val accepted = if (entity.uuid in terrainWaits) service.submitSuspendedSpawn(command) else service.submit(command)
        if (!accepted) entity.physicsPending = false
    }

    private fun drainAcknowledgements() {
        while (true) {
            when (val acknowledgement = service.pollAcknowledgement() ?: return) {
                is PhysicsAcknowledgement.ConnectionCreated -> {
                    val pending = pendingConnections.remove(acknowledgement.requestId) ?: continue
                    if (acknowledgement.success) {
                        if (!pending.weld) confirmedToolConnections += pending.key
                    } else {
                        if (pending.weld) {
                            activeWelds.remove(pending.key)
                            weldHistoryByPlayer.values.forEach { it.removeIf { key -> key == pending.key } }
                        } else if (pending.key !in confirmedToolConnections &&
                            pendingConnections.values.none { !it.weld && it.key == pending.key }
                        ) toolConnections.remove(pending.key)
                        logger.warn("Physics connection creation failed between {} and {}", pending.key.first, pending.key.second)
                        server.playerList.getPlayer(pending.playerId)?.let {
                            it.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.tumble.tool_gun.failed"))
                            sendToolGunState(it)
                        }
                    }
                }
                is PhysicsAcknowledgement.BodyCreated -> {
                    val entity = props[acknowledgement.propId]
                    if (entity == null || entity.isRemoved || !associations.acknowledge(
                            acknowledgement.propId,
                            acknowledgement.requestId,
                            acknowledgement.bodyId
                        )
                    ) {
                        service.submit(
                            PhysicsCommand.DestroyBody(acknowledgement.dimension, acknowledgement.bodyId)
                        )
                    } else {
                        val converted = pendingConversions.remove(acknowledgement.propId)
                        pendingItemConversions.remove(acknowledgement.propId)
                        entity.recoveryOrigin = null
                        checkpointConversion(acknowledgement.propId, entity.level() as ServerLevel)
                        entity.runtimeBodyId = acknowledgement.bodyId
                        entity.physicsPending = false
                        bodyOwners[RuntimeBodyKey(acknowledgement.dimension, acknowledgement.bodyId)] =
                            acknowledgement.propId
                        bodyGenerations.nextGeneration(acknowledgement.propId)
                        converted?.forEach(::notifyConvertedBlock)
                        pendingPhysicsGunHolds.remove(acknowledgement.propId)?.let { playerId ->
                            val player = server.playerList.getPlayer(playerId)
                            if (player != null && player.isAlive && !player.isSpectator &&
                                player.level() === entity.level() && player.isHolding { it.item === TumbleItems.PHYSICS_GUN } &&
                                player.distanceToSqr(entity) <= PHYSICS_GUN_REACH_SQUARED &&
                                playerId !in heldByPlayer && playerId !in heldLivingByPlayer
                            ) hold(player, entity, true)
                        }
                    }
                }
                is PhysicsAcknowledgement.BodyCreationFailed -> {
                    if (!associations.isCurrent(acknowledgement.propId, acknowledgement.requestId)) continue
                    pendingPhysicsGunHolds.remove(acknowledgement.propId)
                    val entity = props[acknowledgement.propId]
                    entity?.physicsPending = false
                    if (rollbackPendingConversion(acknowledgement.propId)) {
                        entity?.discard()
                        if (entity != null) checkpointConversion(entity.uuid, entity.level() as ServerLevel)
                    }
                    else if (entity?.assembly != null && acknowledgement.propId in pendingConversions) {
                        retainFailedAssembly(entity)
                    }
                    logger.error(
                        "Failed to create physics body for prop {}: {}",
                        acknowledgement.propId,
                        acknowledgement.message
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_PENDING_CONVERSIONS = 4096
        const val MAX_DEFERRED_ASSEMBLIES_PER_TICK = 8
        const val MAX_CONVERSIONS_PER_TICK = 64
        const val MAX_PISTON_BOXES = 128
        const val MAX_IMPACTS_PER_TICK = 256
        const val MAX_SHAPE_CONTACT_PASSES = 4
        const val BROADPHASE_MARGIN = 0.05
        const val CONTACT_SKIN = 0.025
        const val TICKS_PER_SECOND = 20.0
        const val MAX_TOTAL_IMPULSE = 0.8
        const val PUSH_REQUEST_REACH = 0.2
        const val PHYSICS_GUN_REACH_SQUARED = 100.0
        const val TOOL_GUN_REACH_SQUARED = 100.0
        const val MIN_PHYSICS_GUN_HOLD_DISTANCE = 1.0
        const val LIVING_ENTITY_LAUNCH_SPEED = 2.5
        const val MAX_HELD_ENTITY_DISTANCE_SQUARED = 256.0
        const val CONVERSION_FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS
        const val MAX_EXPLOSION_DELTA = 40.0
        const val WATER_IDLE_CHECK_TICKS = 10L
        const val PISTON_TRAVEL_SECONDS = 0.1f
        const val PISTON_LIFETIME_STEPS = 6
        const val MAX_PLATFORM_ACTORS_PER_TICK = 128
        const val MAX_ACTORS_PER_PLATFORM = 16
        const val PLATFORM_SEARCH_MARGIN = 0.5
        const val JUMP_VELOCITY = 0.1
        val NATIVE_TUNING_KEYS = setOf(
            TumbleTuningKey.GRAVITY,
            TumbleTuningKey.BUOYANCY,
            TumbleTuningKey.TNT_IMPACT_SPEED
        )
    }
}

private data class UnloadedTerrainWait(
    val level: ServerLevel,
    val propId: UUID,
    val bodyId: PhysicsBodyId,
    val chunks: MutableSet<Long>,
    val watchedChunks: Set<Long>,
    var observedUnload: Boolean,
    val unloadStep: Long
)

object ExplosionMotion {
    fun velocity(
        origin: PhysicsVector,
        target: PhysicsVector,
        radius: Double = 8.0,
        maximumSpeed: Double = 20.0
    ): PhysicsVector? {
        require(radius.isFinite() && radius > 0.0) { "Explosion radius must be positive" }
        require(maximumSpeed.isFinite() && maximumSpeed >= 0.0) { "Explosion speed must be non-negative" }
        val offset = target - origin
        val distance = offset.length()
        if (distance >= radius) return null
        if (distance <= 1e-12) return PhysicsVector(0.0, maximumSpeed, 0.0)
        return offset * (maximumSpeed * (1.0 - distance / radius) / distance)
    }
}

private data class LaunchedProp(
    val shooter: UUID,
    val expiresAt: Long,
    var lastPosition: PhysicsVector,
    val damage: Float
)

private data class ConvertedBlock(
    val level: ServerLevel,
    val position: BlockPos,
    val state: BlockState
)

private data class ConvertedItem(
    val level: ServerLevel,
    val position: PhysicsVector,
    val stack: ItemStack,
    val velocity: PhysicsVector
)

private data class BlockConversion(val position: BlockPos, val linearVelocity: PhysicsVector)

private data class PendingPlacedBlock(val level: ServerLevel, val position: BlockPos)

private data class DroppedBlock(val item: ItemEntity, val stack: ItemStack, val state: BlockState)

private data class PistonMotion(
    val level: ServerLevel,
    val boxes: List<PhysicsBox>,
    val movement: PhysicsVector
)

private data class RuntimeBodyKey(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId)

private data class PlatformMotion(
    val prop: PhysicsPropEntity,
    val level: ServerLevel,
    val previous: AABB,
    val current: AABB,
    val delta: PhysicsVector
)

private data class PendingConnection(val key: WeldKey, val weld: Boolean, val playerId: UUID)

private data class SelectedWeld(val propId: UUID, val face: Direction)

private data class WeldKey(val first: UUID, val second: UUID) {
    fun contains(propId: UUID) = first == propId || second == propId

    companion object {
        fun of(first: UUID, second: UUID) =
            if (first.compareTo(second) <= 0) WeldKey(first, second) else WeldKey(second, first)
    }
}

object ToolGunFace {
    fun selectedFace(player: Player, prop: PhysicsPropEntity): Direction {
        val start = player.eyePosition.subtract(prop.position()).toPhysicsVector()
        val end = player.eyePosition.add(player.lookAngle.scale(10.0)).subtract(prop.position()).toPhysicsVector()
        return fromRay(prop.bodyRotation, start, end)
    }

    fun fromRay(rotation: PhysicsRotation, start: PhysicsVector, end: PhysicsVector): Direction {
        val inverse = rotation.normalized().let { PhysicsRotation(-it.x, -it.y, -it.z, it.w) }
        val localStart = inverse.rotateVector(start)
        val localEnd = inverse.rotateVector(end)
        val hit = AABB(-0.501, -0.501, -0.501, 0.501, 0.501, 0.501)
            .clip(localStart.toVec3(), localEnd.toVec3()).orElse(null)
            ?: return dominantFace(localEnd - localStart)
        val distances = listOf(
            Direction.WEST to kotlin.math.abs(hit.x + 0.501),
            Direction.EAST to kotlin.math.abs(hit.x - 0.501),
            Direction.DOWN to kotlin.math.abs(hit.y + 0.501),
            Direction.UP to kotlin.math.abs(hit.y - 0.501),
            Direction.NORTH to kotlin.math.abs(hit.z + 0.501),
            Direction.SOUTH to kotlin.math.abs(hit.z - 0.501)
        )
        return distances.minBy { it.second }.first
    }

    private fun dominantFace(vector: PhysicsVector): Direction = when {
        kotlin.math.abs(vector.x) >= kotlin.math.abs(vector.y) && kotlin.math.abs(vector.x) >= kotlin.math.abs(vector.z) ->
            if (vector.x < 0.0) Direction.EAST else Direction.WEST
        kotlin.math.abs(vector.y) >= kotlin.math.abs(vector.z) ->
            if (vector.y < 0.0) Direction.UP else Direction.DOWN
        else -> if (vector.z < 0.0) Direction.SOUTH else Direction.NORTH
    }

    private fun PhysicsRotation.rotateVector(vector: PhysicsVector): PhysicsVector {
        val tx = 2.0 * (y * vector.z - z * vector.y)
        val ty = 2.0 * (z * vector.x - x * vector.z)
        val tz = 2.0 * (x * vector.y - y * vector.x)
        return PhysicsVector(
            vector.x + w * tx + y * tz - z * ty,
            vector.y + w * ty + z * tx - x * tz,
            vector.z + w * tz + x * ty - y * tx
        )
    }

    private fun Vec3.toPhysicsVector() = PhysicsVector(x, y, z)
    private fun PhysicsVector.toVec3() = Vec3(x, y, z)
}

private fun PhysicsVector.dot(other: PhysicsVector) = x * other.x + y * other.y + z * other.z

internal fun transfersActorImpulse(normal: PhysicsVector) = normal.y <= 0.5

internal fun actorPushImpulse(speedIntoProp: Double, currentPushSpeed: Double, propMass: Double): Double {
    val targetSpeed = minOf(1.25, 0.35 + speedIntoProp * 0.4)
    return minOf(0.4, speedIntoProp * 0.6, maxOf(0.0, targetSpeed - currentPushSpeed) * propMass)
}

private fun AABB.toPhysicsAabb() = PhysicsAabb(
    PhysicsVector(minX, minY, minZ),
    PhysicsVector(maxX, maxY, maxZ)
)

object GravityGunMotion {
    fun holdVelocity(current: PhysicsVector, target: PhysicsVector): PhysicsVector {
        val velocity = (target - current) * 10.0
        val speed = velocity.length()
        return if (speed > 30.0) velocity * (30.0 / speed) else velocity
    }

    fun holdVelocity(
        current: PhysicsVector,
        target: PhysicsVector,
        mass: Double,
        strength: Double
    ): PhysicsVector {
        require(mass.isFinite() && mass > 0.0) { "Mass must be positive" }
        require(strength.isFinite() && strength > 0.0) { "Strength must be positive" }
        val effectiveMass = maxOf(1.0, mass / 4.0)
        val velocity = (target - current) * (strength / effectiveMass)
        val speed = velocity.length()
        return if (speed > 30.0) velocity * (30.0 / speed) else velocity
    }

    fun livingHoldVelocity(current: PhysicsVector, target: PhysicsVector): PhysicsVector {
        val velocity = (target - current) * 0.5
        val speed = velocity.length()
        return if (speed > 1.5) velocity * (1.5 / speed) else velocity
    }
}

object PhysicsGunRotation {
    fun apply(current: PhysicsRotation, yawDegrees: Double, pitchDegrees: Double, playerYawDegrees: Double): PhysicsRotation {
        val yaw = axisAngle(0.0, 1.0, 0.0, Math.toRadians(-yawDegrees))
        val playerYaw = Math.toRadians(playerYawDegrees)
        val pitch = axisAngle(kotlin.math.cos(playerYaw), 0.0, kotlin.math.sin(playerYaw), Math.toRadians(pitchDegrees))
        return multiply(multiply(yaw, pitch), current.normalized()).normalized()
    }

    private fun axisAngle(x: Double, y: Double, z: Double, angle: Double): PhysicsRotation {
        val half = angle * 0.5
        val sine = kotlin.math.sin(half)
        return PhysicsRotation(x * sine, y * sine, z * sine, kotlin.math.cos(half))
    }

    private fun multiply(a: PhysicsRotation, b: PhysicsRotation) = PhysicsRotation(
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    )
}

object GravityGunDamage {
    fun damage(hardness: Float, bedrock: Boolean = false) = when {
        bedrock -> 20f
        hardness < 0f -> 20f
        else -> (4f + hardness * 2f).coerceAtMost(20f)
    }

    fun sweptCubeHits(from: PhysicsVector, to: PhysicsVector, bounds: PhysicsAabb): Boolean {
        val target = bounds.expanded(0.5)
        val movement = to - from
        var near = 0.0
        var far = 1.0
        for ((start, delta, minimum, maximum) in listOf(
            Axis(from.x, movement.x, target.min.x, target.max.x),
            Axis(from.y, movement.y, target.min.y, target.max.y),
            Axis(from.z, movement.z, target.min.z, target.max.z)
        )) {
            if (kotlin.math.abs(delta) < 1e-12) {
                if (start < minimum || start > maximum) return false
                continue
            }
            val first = (minimum - start) / delta
            val second = (maximum - start) / delta
            near = maxOf(near, minOf(first, second))
            far = minOf(far, maxOf(first, second))
            if (near > far) return false
        }
        return true
    }

    private data class Axis(val start: Double, val delta: Double, val minimum: Double, val maximum: Double)
}

data class NetworkProp(
    val propId: UUID,
    val dimension: PhysicsDimensionId,
    val bodyId: PhysicsBodyId,
    val bodyGeneration: Long
)

internal fun MutableMap<UUID, Long>.nextGeneration(propId: UUID): Long =
    ((this[propId] ?: 0L) + 1L).also { this[propId] = it }

internal fun isRecentPhysicalDrop(age: Int) = age in 0..2

enum class ToolGunWeldResult { SELECTED, WELDED, CANCELLED, FAILED }
