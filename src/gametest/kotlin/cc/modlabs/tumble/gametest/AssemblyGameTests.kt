// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.assembly.AssemblyBlock
import cc.modlabs.tumble.assembly.AssemblyDefinition
import cc.modlabs.tumble.assembly.AssemblyGeometry
import cc.modlabs.tumble.assembly.AssemblyStructure
import cc.modlabs.tumble.assembly.AssemblyCollisionBox
import cc.modlabs.tumble.physics.PhysicsBodyDefinition
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsSectionPosition
import cc.modlabs.tumble.physics.PhysicsDimensionId
import cc.modlabs.tumble.physics.PhysicsAcknowledgement
import cc.modlabs.tumble.physics.PhysicsCommand
import cc.modlabs.tumble.physics.ServerPhysicsService
import cc.modlabs.tumble.physics.TerrainSectionTracker
import cc.modlabs.tumble.physics.PhysicsPropManager
import cc.modlabs.tumble.physics.TumbleTuning
import cc.modlabs.tumble.physics.TerrainSectionKey
import cc.modlabs.tumble.physics.physicsDimensionId
import cc.modlabs.tumble.physics.PhysicsTransform
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.PhysicsWorld
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.physics.ToolGunWeldResult
import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.network.AssemblyPayload
import com.mojang.serialization.JsonOps
import io.netty.buffer.Unpooled
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

class AssemblyGameTests {
    @GameTest(maxTicks = 400)
    fun failedNativeWeldCanBeRetried(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 3, 1))
        val first = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        val second = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn second prop")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        var retry = ToolGunWeldResult.FAILED
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            when (phase) {
                0 -> {
                    val bodies = runtime.manager.networkProps().associateBy { it.propId }
                    val firstBody = bodies[first.uuid] ?: return@succeedWhen helper.assertTrue(false, "first body not ready")
                    if (bodies[second.uuid] == null) return@succeedWhen helper.assertTrue(false, "second body not ready")
                    runtime.service.submit(PhysicsCommand.DestroyBody(dimension, firstBody.bodyId))
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "selection failed")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld was not queued")
                    checkpoint = frame.physicsStep
                    phase = 1
                }
                1 -> if (frame.physicsStep - checkpoint >= 20) {
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "retry selection failed")
                    retry = runtime.manager.useToolGunWeld(player, second)
                    phase = 2
                }
                2 -> {
                    helper.assertTrue(retry == ToolGunWeldResult.WELDED, "failed native weld left a phantom connection: $retry")
                    runtime.manager.entityUnloaded(first, helper.level)
                    runtime.manager.entityUnloaded(second, helper.level)
                    first.discard()
                    second.discard()
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for failed weld acknowledgement")
        }
    }

    @GameTest(maxTicks = 1200)
    fun linkedPropReloadWaitsForMissingAssemblyTerrain(helper: GameTestHelper) = checkLinkedReload(helper, 0)

    @GameTest(maxTicks = 1200)
    fun immediatelySavedLinkedPropWaitsForMissingAssemblyTerrain(helper: GameTestHelper) = checkLinkedReload(helper, 1)

    @GameTest(maxTicks = 1200)
    fun linkedPropSavedBeforeVisibilityChangesWaitsForAssembly(helper: GameTestHelper) = checkLinkedReload(helper, 2)

    private fun checkLinkedReload(helper: GameTestHelper, ordering: Int) {
        val origin = BlockPos(3104 + ordering * 128, 50, 0)
        val remote = origin.offset(32, 0, 0)
        for (position in listOf(origin, remote)) {
            helper.level.setChunkForced(position.x shr 4, 0, true)
            helper.level.getChunkAt(position)
        }
        for (x in 0..5) for (z in 0..3) helper.level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(remote.below(), Blocks.STONE.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(32, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(31.5, -0.5, -0.5), PhysicsVector(32.5, 0.5, 0.5)))
        ))
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        assembly.initializeAssembly(structure)
        helper.assertTrue(helper.level.addFreshEntity(assembly), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(assembly, helper.level)
        var other = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, 50.5, 0.5), stone)
            ?: return helper.fail("could not create linked prop")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, 50.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        var weldFailure = "none"
        var undoWhilePaused: Boolean? = null
        fun reloadOther(step: Long, otherFirst: Boolean = false) {
            val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
            other.saveWithoutId(saved)
            if (otherFirst) {
                runtime.manager.entityUnloaded(other, helper.level, true)
                other.discard()
            }
            runtime.manager.entityUnloaded(assembly, helper.level, true)
            assembly.discard()
            if (otherFirst) PhysicsServices.chunkUnloaded(helper.level, remote.x shr 4, 0)
            else {
                runtime.manager.entityUnloaded(other, helper.level, true)
                other.discard()
            }
            val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
                ?: error("could not reload linked prop")
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), saved.buildResult()))
            helper.assertTrue(helper.level.addFreshEntity(restored), "could not add reloaded prop")
            runtime.manager.entityLoaded(restored, helper.level)
            other = restored
            checkpoint = step
            phase = 3
        }
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    helper.assertTrue(assembly.uuid in ready && other.uuid in ready, "physics bodies are not ready")
                    val selected = runtime.manager.useToolGunWeld(player, assembly)
                    val welded = runtime.manager.useToolGunWeld(player, other)
                    weldFailure = "selection=$selected, weld=$welded, root=${TumbleApi.status(helper.level, assembly.uuid)}, other=${TumbleApi.status(helper.level, other.uuid)}"
                    if (selected != ToolGunWeldResult.SELECTED || welded != ToolGunWeldResult.WELDED) phase = 5
                    helper.assertTrue(phase != 5, weldFailure)
                    checkpoint = frame.physicsStep
                    phase = 1
                }
                1 -> if (frame.physicsStep - checkpoint >= 10) {
                    if (ordering == 2) reloadOther(frame.physicsStep, otherFirst = true)
                    else {
                        PhysicsServices.chunkUnloaded(helper.level, remote.x shr 4, 0)
                        helper.assertTrue(TumbleApi.status(helper.level, assembly.uuid) == TumbleApi.PropStatus.PAUSED, "assembly did not enter terrain wait")
                        if (undoWhilePaused == null) undoWhilePaused = runtime.manager.undoToolGunWeld(player)
                        helper.assertTrue(undoWhilePaused == false, "undo broke a terrain-paused group")
                        checkpoint = frame.physicsStep
                        if (ordering == 1) reloadOther(frame.physicsStep) else phase = 2
                    }
                }
                2 -> if (frame.physicsStep - checkpoint >= 40) {
                    val snapshot = TumbleApi.snapshot(helper.level, other.uuid)
                        ?: return@succeedWhen helper.assertTrue(false, "linked body is not ready")
                    helper.assertTrue(snapshot.suspended, "linked body was not paused")
                    reloadOther(frame.physicsStep)
                }
                3 -> if (frame.physicsStep - checkpoint >= 30) {
                    val status = TumbleApi.status(helper.level, other.uuid)
                    helper.assertTrue((status == TumbleApi.PropStatus.PENDING || status == TumbleApi.PropStatus.PAUSED) &&
                        TumbleApi.snapshot(helper.level, other.uuid) == null,
                        "reloaded prop started while assembly terrain was missing: $status")
                    PhysicsServices.chunkLoaded(helper.level, remote.x shr 4, 0)
                    phase = 4
                }
                4 -> if (TumbleApi.status(helper.level, other.uuid) == TumbleApi.PropStatus.READY) return@succeedWhen
                5 -> helper.assertTrue(false, weldFailure)
            }
            helper.assertTrue(false, "waiting for linked reload, phase=$phase")
        }
    }

    @GameTest(maxTicks = 800)
    fun linkedPropDoesNotStayPausedWhenAssemblyChangesDimension(helper: GameTestHelper) {
        val origin = BlockPos(2944, 50, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        for (x in 0..5) for (z in 0..3) helper.level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val structure = AssemblyStructure.of(AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone))), listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        assembly.initializeAssembly(structure)
        helper.assertTrue(helper.level.addFreshEntity(assembly), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(assembly, helper.level)
        val other = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, 50.5, 0.5), stone)
            ?: return helper.fail("could not create linked prop")
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            val snapshot = TumbleApi.snapshot(helper.level, other.uuid)
                ?: return@succeedWhen helper.assertTrue(false, "linked body is not ready, phase=$phase, status=${TumbleApi.status(helper.level, other.uuid)}, service=${runtime.service.debugState()}")
            when (phase) {
                0 -> {
                    val root = runtime.manager.networkProps().firstOrNull { it.propId == assembly.uuid }
                        ?: return@succeedWhen helper.assertTrue(false, "assembly body is not ready")
                    val linked = runtime.manager.networkProps().first { it.propId == other.uuid }
                    runtime.service.submit(PhysicsCommand.CreateWeld(dimension, root.bodyId, linked.bodyId))
                    checkpoint = frame.physicsStep
                    phase = 1
                }
                1 -> if (frame.physicsStep - checkpoint >= 10) {
                    assembly.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION)
                    runtime.manager.entityUnloaded(assembly, helper.level, true)
                    checkpoint = frame.physicsStep
                    phase = 2
                }
                2 -> if (frame.physicsStep - checkpoint >= 30) {
                    helper.assertTrue(!snapshot.suspended, "dimension transfer stranded the linked prop")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for dimension transfer, phase=$phase")
        }
    }

    @GameTest(maxTicks = 800)
    fun linkedPropWaitsForTerrainAfterAssemblyUnloads(helper: GameTestHelper) = checkLinkedUnload(helper, false)

    @GameTest(maxTicks = 800)
    fun linkedPropWaitsWhenAssemblyUnloadsBeforeTerrain(helper: GameTestHelper) = checkLinkedUnload(helper, true)

    private fun checkLinkedUnload(helper: GameTestHelper, unloadFirst: Boolean) {
        val origin = BlockPos(if (unloadFirst) 2576 else 1728, 50, 0)
        val remote = origin.offset(32, 0, 0)
        for (position in listOf(origin, remote)) {
            helper.level.setChunkForced(position.x shr 4, 0, true)
            helper.level.getChunkAt(position)
        }
        for (x in 0..5) for (z in 0..3) helper.level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(remote.below(), Blocks.STONE.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(32, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(31.5, -0.5, -0.5), PhysicsVector(32.5, 0.5, 0.5)))
        ))
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        assembly.initializeAssembly(structure)
        helper.assertTrue(helper.level.addFreshEntity(assembly), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(assembly, helper.level)
        val other = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, 50.5, 0.5), stone)
            ?: return helper.fail("could not create linked prop")
        val unrelatedOrigin = BlockPos(if (unloadFirst) 2800 else 2400, 50, 0)
        helper.level.setChunkForced(unrelatedOrigin.x shr 4, 0, true)
        helper.level.getChunkAt(unrelatedOrigin)
        helper.assertTrue(!helper.level.chunkSource.hasChunk((unrelatedOrigin.x + 64) shr 4, 0), "unrelated remote chunk is unexpectedly loaded")
        val unrelatedDefinition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(64, 0, 0), stone)))
        val unrelatedStructure = AssemblyStructure.of(unrelatedDefinition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(63.5, -0.5, -0.5), PhysicsVector(64.5, 0.5, 0.5)))
        ))
        val unrelated = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        unrelated.setPos(unrelatedOrigin.x + 0.5, unrelatedOrigin.y + 0.5, 0.5)
        unrelated.initializeAssembly(unrelatedStructure)
        helper.assertTrue(helper.level.addFreshEntity(unrelated), "could not add unrelated assembly")
        runtime.manager.entityLoaded(unrelated, helper.level)
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            val snapshot = TumbleApi.snapshot(helper.level, other.uuid)
                ?: return@succeedWhen helper.assertTrue(false, "linked body is not ready")
            when (phase) {
                0 -> {
                    val root = runtime.manager.networkProps().firstOrNull { it.propId == assembly.uuid }
                        ?: return@succeedWhen helper.assertTrue(false, "assembly body is not ready")
                    val linked = runtime.manager.networkProps().first { it.propId == other.uuid }
                    runtime.service.submit(PhysicsCommand.CreateWeld(dimension, root.bodyId, linked.bodyId))
                    checkpoint = frame.physicsStep
                    phase = 1
                }
                1 -> if (frame.physicsStep - checkpoint >= 10) {
                    if (unloadFirst) {
                        runtime.manager.entityUnloaded(assembly, helper.level, true)
                        assembly.discard()
                    }
                    PhysicsServices.chunkUnloaded(helper.level, remote.x shr 4, 0)
                    checkpoint = frame.physicsStep
                    phase = 2
                }
                2 -> if (frame.physicsStep - checkpoint >= 30) {
                    helper.assertTrue(snapshot.suspended, "linked prop did not pause when terrain unloaded")
                    if (!unloadFirst) {
                        runtime.manager.entityUnloaded(assembly, helper.level, true)
                        assembly.discard()
                    }
                    checkpoint = frame.physicsStep
                    phase = 3
                }
                3 -> if (frame.physicsStep - checkpoint >= 30) {
                    helper.assertTrue(snapshot.suspended, "linked prop resumed while terrain was missing")
                    PhysicsServices.chunkLoaded(helper.level, remote.x shr 4, 0)
                    checkpoint = frame.physicsStep
                    phase = 4
                }
                4 -> if (frame.physicsStep - checkpoint >= 30) {
                    helper.assertTrue(!snapshot.suspended, "linked prop remained paused after terrain returned")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for linked unload, phase=$phase")
        }
    }

    @GameTest(maxTicks = 2500)
    fun assemblyPausesIfTerrainUnloadsBeforeBodyAcknowledgement(helper: GameTestHelper) {
        val origin = BlockPos(1664, 50, 0)
        val remote = origin.offset(32, 0, 0)
        for (position in listOf(origin, remote)) {
            helper.level.setChunkForced(position.x shr 4, 0, true)
            helper.level.getChunkAt(position)
        }
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(32, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(31.5, -0.5, -0.5), PhysicsVector(32.5, 0.5, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        entity.setDeltaMovement(2.0, 0.0, 0.0)
        helper.assertTrue(helper.level.addFreshEntity(entity), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(entity, helper.level)
        PhysicsServices.chunkUnloaded(helper.level, remote.x shr 4, 0)
        helper.assertTrue(TumbleApi.status(helper.level, entity.uuid) == TumbleApi.PropStatus.PAUSED, "creation lost the terrain pause")
        helper.succeedWhen {
            Thread.sleep(5)
            val snapshot = TumbleApi.snapshot(helper.level, entity.uuid)
                ?: return@succeedWhen helper.assertTrue(false, "assembly body is not ready")
            helper.assertTrue(snapshot.suspended, "created assembly was not suspended")
        }
    }

    @GameTest(maxTicks = 800)
    fun movingAssemblyPausesAndResumesAcrossTerrainUnload(helper: GameTestHelper) {
        val origin = BlockPos(1536, 50, 0)
        val remote = origin.offset(32, 0, 0)
        for (position in listOf(origin, remote)) {
            helper.level.setChunkForced(position.x shr 4, 0, true)
            helper.level.getChunkAt(position)
        }
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(32, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(31.5, -0.5, -0.5), PhysicsVector(32.5, 0.5, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        entity.setDeltaMovement(2.0, 0.0, 0.0)
        helper.assertTrue(helper.level.addFreshEntity(entity), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(entity, helper.level)
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        var pausedX = 0.0
        helper.succeedWhen {
            Thread.sleep(5)
            val snapshot = TumbleApi.snapshot(helper.level, entity.uuid)
                ?: return@succeedWhen helper.assertTrue(false, "assembly body is not ready")
            val step = runtime.service.latestFrame(dimension).physicsStep
            when (phase) {
                0 -> {
                    val start = PhysicsVector(origin.x + 0.5, 200.5, origin.z + 0.5)
                    entity.setPos(start.x, start.y, start.z)
                    runtime.service.submit(PhysicsCommand.SetTransform(dimension, snapshot.bodyId, PhysicsTransform(start)))
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, snapshot.bodyId, PhysicsVector(2.0, 0.0, 0.0)))
                    PhysicsServices.chunkUnloaded(helper.level, remote.x shr 4, 0)
                    helper.assertTrue(TumbleApi.status(helper.level, entity.uuid) == TumbleApi.PropStatus.PAUSED, "unavailable terrain did not pause the API")
                    helper.assertTrue(!TumbleApi.applyImpulse(helper.level, entity.uuid, net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0)), "paused assembly accepted an impulse")
                    checkpoint = step
                    phase = 1
                }
                1 -> if (step - checkpoint >= 30) {
                    pausedX = snapshot.transform.position.x
                    checkpoint = step
                    phase = 2
                }
                2 -> if (step - checkpoint >= 30) {
                    helper.assertTrue(kotlin.math.abs(snapshot.transform.position.x - pausedX) < 0.05, "assembly moved while terrain was unavailable")
                    PhysicsServices.chunkLoaded(helper.level, remote.x shr 4, 0)
                    checkpoint = step
                    phase = 3
                }
                3 -> if (step - checkpoint >= 30) {
                    helper.assertTrue(snapshot.transform.position.x > pausedX + 0.2,
                        "assembly did not resume its motion: paused=$pausedX, x=${snapshot.transform.position.x}, velocity=${snapshot.linearVelocity.x}, suspended=${snapshot.suspended}, status=${TumbleApi.status(helper.level, entity.uuid)}")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for terrain pause, phase=$phase")
        }
    }

    @GameTest
    fun stoppedPhysicsServiceDoesNotConsumeAssemblyBlocks(helper: GameTestHelper) {
        val origin = BlockPos(1472, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val logger = LoggerFactory.getLogger("assembly-stopped-service-test")
        val service = ServerPhysicsService(logger)
        service.close()
        val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning())
        val method = manager.javaClass.declaredMethods.single { it.name.startsWith("convertAssembly") && it.parameterCount == 4 }
        var created: PhysicsPropEntity? = null
        try {
            created = method.invoke(manager, helper.level, listOf(origin, origin.east()), player, false) as? PhysicsPropEntity
            helper.assertTrue(created == null, "conversion was accepted by a stopped physics service")
            helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE) && helper.level.getBlockState(origin.east()).`is`(Blocks.STONE),
                "stopped service consumed the source blocks")
        } finally {
            created?.let {
                manager.entityUnloaded(it, helper.level)
                PhysicsServices.runtime(helper.level.server)?.manager?.entityUnloaded(it, helper.level)
                it.discard()
            }
        }
        helper.succeed()
    }

    @GameTest
    fun operatorCommandConvertsSelectedBlocks(helper: GameTestHelper) {
        val origin = BlockPos(1408, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), Blocks.OAK_STAIRS.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayerInLevel()
        player.setGameMode(GameType.CREATIVE)
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val operator = NameAndId(player.gameProfile)
        helper.level.server.playerList.op(operator, java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty())
        val result = try {
            helper.level.server.commands.dispatcher.execute(
                "tumble assembly ${origin.x} ${origin.y} ${origin.z} ${origin.x + 1} ${origin.y} ${origin.z}",
                player.createCommandSourceStack()
            )
        } finally { helper.level.server.playerList.deop(operator) }
        helper.assertTrue(result == 1 && helper.level.getBlockState(origin).isAir && helper.level.getBlockState(origin.east()).isAir,
            "assembly command did not convert the selection")
        player.discard()
        helper.succeed()
    }

    @GameTest
    fun failedAssemblyCreationRestoresEveryOriginalBlock(helper: GameTestHelper) {
        val origin = BlockPos(1344, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        val stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), stair, 3)
        val originalStair = helper.level.getBlockState(origin.east())
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, listOf(origin, origin.east()), player)
            ?: return helper.fail("could not start assembly conversion")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val field = runtime.service.javaClass.getDeclaredField("acknowledgements")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val acknowledgements = field.get(runtime.service) as ConcurrentLinkedQueue<PhysicsAcknowledgement>
        val deadline = System.nanoTime() + 5_000_000_000L
        var created: PhysicsAcknowledgement.BodyCreated? = null
        while (created == null && System.nanoTime() < deadline) {
            created = acknowledgements.filterIsInstance<PhysicsAcknowledgement.BodyCreated>().firstOrNull { it.propId == id }
            if (created == null) Thread.sleep(1)
        }
        val body = created ?: return helper.fail("physics worker did not create the test body")
        helper.assertTrue(acknowledgements.remove(body), "could not intercept the acknowledgement")
        runtime.service.submit(PhysicsCommand.DestroyBody(body.dimension, body.bodyId))
        acknowledgements.add(PhysicsAcknowledgement.BodyCreationFailed(id, "injected assembly failure", body.requestId))
        runtime.manager.tick()
        helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE) && helper.level.getBlockState(origin.east()) == originalStair,
            "failed assembly did not restore the original blocks")
        helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.UNAVAILABLE, "failed assembly remained alongside restored blocks")
        helper.succeed()
    }

    @GameTest
    fun pendingAssemblySavesRecoveryDataBeforeBodyCreation(helper: GameTestHelper) {
        val origin = BlockPos(2272, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, listOf(origin), player)
            ?: return helper.fail("could not start assembly conversion")
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val field = manager.javaClass.getDeclaredField("props").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val entity = (field.get(manager) as Map<UUID, PhysicsPropEntity>)[id] ?: return helper.fail("assembly missing")
        val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        entity.saveWithoutId(saved)
        val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
            ?: return helper.fail("could not reload assembly")
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), saved.buildResult()))
        val recovery = restored.javaClass.getDeclaredField("recoveryOrigin").apply { isAccessible = true }
        helper.assertTrue(recovery.get(restored) == origin, "pending assembly did not save its recovery origin")
        helper.succeed()
    }

    @GameTest
    fun partiallyRemovedAssemblyCanBeRecovered(helper: GameTestHelper) {
        val origin = BlockPos(2208, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        val stone = Blocks.STONE.defaultBlockState()
        val stairs = Blocks.OAK_STAIRS.defaultBlockState()
        helper.level.setBlock(origin, stone, 3)
        helper.level.setBlock(origin.east(), Blocks.AIR.defaultBlockState(), 3)
        val definition = AssemblyDefinition.of(listOf(
            AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(1, 0, 0), stairs)
        ))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(0.5, -0.5, -0.5), PhysicsVector(1.5, 0.0, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        val recovery = entity.javaClass.getDeclaredField("recoveryOrigin")
        recovery.isAccessible = true
        recovery.set(entity, origin)
        val logger = LoggerFactory.getLogger("partial-assembly-recovery-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning())
        try {
            manager.entityLoaded(entity, helper.level)
            manager.tick()
            helper.assertTrue(helper.level.getBlockState(origin) == stone && helper.level.getBlockState(origin.east()) == stairs,
                "recovery did not restore the removed block while retaining the original block")
            helper.assertTrue(entity.isRemoved, "recovered assembly was not removed")
        } finally {
            service.close()
        }
        helper.succeed()
    }

    @GameTest
    fun blockedAssemblyRollbackRecoversAfterTheObstructionIsRemoved(helper: GameTestHelper) {
        val origin = BlockPos(1824, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), Blocks.OAK_STAIRS.defaultBlockState(), 3)
        val expected = helper.level.getBlockState(origin.east())
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, listOf(origin, origin.east()), player)
            ?: return helper.fail("could not start assembly conversion")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val field = runtime.service.javaClass.getDeclaredField("acknowledgements")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val acknowledgements = field.get(runtime.service) as ConcurrentLinkedQueue<PhysicsAcknowledgement>
        val deadline = System.nanoTime() + 5_000_000_000L
        var created: PhysicsAcknowledgement.BodyCreated? = null
        while (created == null && System.nanoTime() < deadline) {
            created = acknowledgements.filterIsInstance<PhysicsAcknowledgement.BodyCreated>().firstOrNull { it.propId == id }
            if (created == null) Thread.sleep(1)
        }
        val body = created ?: return helper.fail("physics worker did not create the test body")
        helper.assertTrue(acknowledgements.remove(body), "could not intercept the acknowledgement")
        runtime.service.submit(PhysicsCommand.DestroyBody(body.dimension, body.bodyId))
        helper.level.setBlock(origin.east(), Blocks.DIRT.defaultBlockState(), 3)
        acknowledgements.add(PhysicsAcknowledgement.BodyCreationFailed(id, "injected assembly failure", body.requestId))
        runtime.manager.tick()
        helper.assertTrue(helper.level.getBlockState(origin.east()).`is`(Blocks.DIRT), "rollback overwrote the obstruction")
        helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.FAILED, "blocked rollback lost the assembly")
        helper.assertTrue(!TumbleApi.remove(helper.level, id), "recovering assembly was removed")
        helper.level.setBlock(origin.east(), Blocks.AIR.defaultBlockState(), 3)
        runtime.manager.tick()
        helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE) && helper.level.getBlockState(origin.east()) == expected,
            "assembly was not recovered after the obstruction was removed")
        helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.UNAVAILABLE, "recovered assembly was not removed")
        helper.succeed()
    }

    @GameTest
    fun blockedAssemblyRecoverySurvivesSavingAndReloading(helper: GameTestHelper) {
        val origin = BlockPos(1888, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), Blocks.OAK_STAIRS.defaultBlockState(), 3)
        val expected = helper.level.getBlockState(origin.east())
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, listOf(origin, origin.east()), player)
            ?: return helper.fail("could not start assembly conversion")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val field = runtime.service.javaClass.getDeclaredField("acknowledgements")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val acknowledgements = field.get(runtime.service) as ConcurrentLinkedQueue<PhysicsAcknowledgement>
        val deadline = System.nanoTime() + 5_000_000_000L
        var created: PhysicsAcknowledgement.BodyCreated? = null
        while (created == null && System.nanoTime() < deadline) {
            created = acknowledgements.filterIsInstance<PhysicsAcknowledgement.BodyCreated>().firstOrNull { it.propId == id }
            if (created == null) Thread.sleep(1)
        }
        val body = created ?: return helper.fail("physics worker did not create the test body")
        helper.assertTrue(acknowledgements.remove(body), "could not intercept the acknowledgement")
        runtime.service.submit(PhysicsCommand.DestroyBody(body.dimension, body.bodyId))
        helper.level.setBlock(origin.east(), Blocks.DIRT.defaultBlockState(), 3)
        acknowledgements.add(PhysicsAcknowledgement.BodyCreationFailed(id, "injected assembly failure", body.requestId))
        runtime.manager.tick()
        val propsField = runtime.manager.javaClass.getDeclaredField("props")
        propsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val props = propsField.get(runtime.manager) as Map<UUID, PhysicsPropEntity>
        val original = props[id] ?: return helper.fail("failed assembly is missing")
        val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        original.saveWithoutId(saved)
        runtime.manager.entityUnloaded(original, helper.level, true)
        original.discard()
        val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
            ?: return helper.fail("could not reload assembly")
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), saved.buildResult()))
        helper.assertTrue(helper.level.addFreshEntity(restored), "could not add reloaded assembly")
        runtime.manager.entityLoaded(restored, helper.level)
        helper.assertTrue(TumbleApi.status(helper.level, restored.uuid) == TumbleApi.PropStatus.FAILED, "reload lost pending recovery")
        helper.level.setBlock(origin.east(), Blocks.AIR.defaultBlockState(), 3)
        runtime.manager.tick()
        helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE) && helper.level.getBlockState(origin.east()) == expected,
            "reloaded assembly did not restore the captured blocks")
        helper.succeed()
    }

    @GameTest
    fun blockedRecoveriesDoNotStarveLaterAssemblies(helper: GameTestHelper) {
        val service = ServerPhysicsService(LoggerFactory.getLogger("assembly-recovery-fairness-test"))
        service.start()
        val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), LoggerFactory.getLogger("assembly-recovery-fairness-test"), TumbleTuning())
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val start = BlockPos(2112, 4, 0)
        for (chunk in (start.x shr 4)..((start.x + 32) shr 4)) {
            helper.level.setChunkForced(chunk, 0, true)
            helper.level.getChunkAt(BlockPos(chunk shl 4, 4, 0))
        }
        repeat(9) { index ->
            val origin = start.offset(index * 4, 0, 0)
            helper.level.setBlock(origin, if (index < 8) Blocks.DIRT.defaultBlockState() else Blocks.AIR.defaultBlockState(), 3)
            val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
            entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
            entity.initializeAssembly(structure)
            val recovery = entity.javaClass.getDeclaredField("recoveryOrigin")
            recovery.isAccessible = true
            recovery.set(entity, origin)
            manager.entityLoaded(entity, helper.level)
        }
        try {
            repeat(2) { manager.tick() }
            helper.assertTrue(helper.level.getBlockState(start.offset(32, 0, 0)).`is`(Blocks.STONE), "later recovery was starved")
        } finally {
            service.close()
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 3000)
    fun operatorCanConvertA250BlockStaircaseIntoOneBody(helper: GameTestHelper) {
        val origin = BlockPos(1280, 4, 0)
        for (chunk in (origin.x shr 4)..((origin.x + 25) shr 4)) {
            helper.level.setChunkForced(chunk, 0, true)
            helper.level.getChunkAt(BlockPos(chunk shl 4, 4, 0))
        }
        val positions = (0 until 25).flatMap { step ->
            (0 until 5).flatMap { width -> listOf(origin.offset(step, step, width), origin.offset(step + 1, step, width)) }
        }
        positions.forEachIndexed { index, position ->
            helper.level.setBlock(position, if (index % 2 == 0) Blocks.OAK_STAIRS.defaultBlockState() else Blocks.STONE.defaultBlockState(), 3)
        }
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        helper.assertTrue(TumbleApi.convertAssemblyAsOperator(helper.level, positions, player) == null,
            "non-operator was allowed to convert a distant structure")
        val operator = NameAndId(player.gameProfile)
        helper.level.server.playerList.op(operator, java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty())
        helper.assertTrue(net.minecraft.commands.Commands.hasPermission<net.minecraft.commands.CommandSourceStack>(
            net.minecraft.commands.Commands.LEVEL_GAMEMASTERS
        ).test(player.createCommandSourceStack()), "operator permissions were not applied")
        val id = try { TumbleApi.convertAssemblyAsOperator(helper.level, positions, player) }
            finally { helper.level.server.playerList.deop(operator) }
            ?: return helper.fail("operator could not convert the staircase")
        helper.assertTrue(positions.all { helper.level.getBlockState(it).isAir }, "not all staircase blocks were converted")
        helper.succeedWhen {
            Thread.sleep(5)
            val prop = helper.level.getEntity(id) as? PhysicsPropEntity
                ?: return@succeedWhen helper.assertTrue(false, "assembly entity is not loaded")
            helper.assertTrue(prop.assembly?.definition?.blocks?.size == 250, "staircase block count changed")
            helper.assertTrue(TumbleApi.snapshot(helper.level, id) != null, "staircase body is not ready")
        }
    }

    @GameTest(maxTicks = 1500)
    fun reloadedAssemblyWaitsForDistantTerrain(helper: GameTestHelper) {
        val origin = BlockPos(960, 10, 0)
        val remote = origin.offset(64, 0, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        helper.assertTrue(!helper.level.chunkSource.hasChunk(remote.x shr 4, 0), "remote chunk was unexpectedly loaded")
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(64, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(63.5, -0.5, -0.5), PhysicsVector(64.5, 0.5, 0.5)))
        ))
        val original = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        original.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        original.initializeAssembly(structure)
        val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        original.saveWithoutId(saved)
        val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
            ?: return helper.fail("could not restore assembly")
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), saved.buildResult()))
        helper.assertTrue(helper.level.addFreshEntity(restored), "could not add restored assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(restored, helper.level)
        val dimension = helper.level.physicsDimensionId()
        val start = runtime.service.latestFrame(dimension).physicsStep
        var loadedRemote = false
        helper.succeedWhen {
            Thread.sleep(5)
            if (!loadedRemote) {
                helper.assertTrue(runtime.service.latestFrame(dimension).physicsStep - start >= 60, "waiting for physics")
                helper.assertTrue(TumbleApi.status(helper.level, restored.uuid) == TumbleApi.PropStatus.PENDING,
                    "assembly started before its distant chunk was loaded")
                helper.level.setChunkForced(remote.x shr 4, 0, true)
                helper.level.getChunkAt(remote)
                helper.level.setBlock(remote.below(10), stone, 3)
                loadedRemote = true
                return@succeedWhen helper.assertTrue(false, "waiting for the assembly body")
            }
            helper.assertTrue(TumbleApi.status(helper.level, restored.uuid) == TumbleApi.PropStatus.READY,
                "assembly did not resume after terrain loaded")
        }
    }

    @GameTest(maxTicks = 1200)
    fun actorCollidesWithTheFarEndOfAnAssembly(helper: GameTestHelper) {
        val origin = BlockPos(832, 4, 0)
        for (position in listOf(origin, origin.offset(32, 0, 0))) {
            helper.level.setChunkForced(position.x shr 4, 0, true)
            helper.level.getChunkAt(position)
        }
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(32, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(31.5, -0.5, -0.5), PhysicsVector(32.5, 0.5, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        helper.assertTrue(helper.level.addFreshEntity(entity), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(entity, helper.level)
        val actor = helper.makeMockPlayer(GameType.CREATIVE)
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(runtime.manager.networkProps().any { it.propId == entity.uuid }, "assembly body is not ready")
            actor.setPos(entity.x + 32.0, entity.y + 0.7, entity.z)
            val movement = runtime.manager.clipActorMovement(actor, net.minecraft.world.phys.Vec3(0.0, -1.0, 0.0))
            helper.assertTrue(movement.y > -0.8, "actor passed through the far assembly block")
        }
    }

    @GameTest
    fun assemblyNetworkPayloadPreservesStatesAndCollision(helper: GameTestHelper) {
        val stone = Blocks.STONE.defaultBlockState()
        val stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST)
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(1, 0, 0), stair)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(0.5, -0.5, -0.5), PhysicsVector(1.5, 0.0, 0.5)))
        ))
        val id = UUID.randomUUID()
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            AssemblyPayload.CODEC.encode(buffer, AssemblyPayload(id, PhysicsDimensionId("minecraft:overworld"), structure))
            val decoded = AssemblyPayload.CODEC.decode(buffer)
            helper.assertTrue(decoded.propId == id && decoded.structure.definition.blocks == definition.blocks, "network changed block states")
            helper.assertTrue(decoded.structure.parts == structure.parts, "network changed collision boxes")
        } finally {
            buffer.release()
        }
        val invalid = FriendlyByteBuf(Unpooled.buffer())
        try {
            invalid.writeUUID(id).writeUtf("minecraft:overworld", 128).writeVarInt(AssemblyDefinition.MAX_BLOCKS + 1)
            val rejected = try { AssemblyPayload.CODEC.decode(invalid); false } catch (_: IllegalArgumentException) { true }
            helper.assertTrue(rejected, "oversized network palette was accepted")
        } finally {
            invalid.release()
        }
        helper.succeed()
    }

    @GameTest
    fun terrainTrackingIncludesTheFarEndOfAnAssembly(helper: GameTestHelper) {
        val origin = BlockPos(768, 4, 0)
        val remote = origin.offset(32, 0, 0)
        for (position in listOf(origin, origin.offset(16, 0, 0), remote)) {
            helper.level.setChunkForced(position.x shr 4, 0, true)
            helper.level.getChunkAt(position)
        }
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(32, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(31.5, -0.5, -0.5), PhysicsVector(32.5, 0.5, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        helper.assertTrue(helper.level.addFreshEntity(entity), "could not add assembly")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(entity, helper.level)
        val field = runtime.terrain.javaClass.getDeclaredField("synced")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val synced = field.get(runtime.terrain) as Set<TerrainSectionKey>
        val remoteSection = TerrainSectionKey(helper.level.physicsDimensionId(), PhysicsSectionPosition(remote.x shr 4, remote.y shr 4, 0))
        helper.assertTrue(remoteSection in synced, "remote terrain was not captured before body creation")
        runtime.manager.tick()
        helper.assertTrue(remoteSection in synced, "terrain at the far end was discarded")
        helper.succeed()
    }

    @GameTest
    fun playerCannotConvertABlockBeyondReachThroughAnAssembly(helper: GameTestHelper) {
        val origin = BlockPos(736, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        val positions = (0..12).map { origin.offset(it, 0, 0) }
        positions.forEach { helper.level.setBlock(it, Blocks.STONE.defaultBlockState(), 3) }
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        helper.assertTrue(TumbleApi.convertAssembly(helper.level, positions, player) == null, "distant tail was converted")
        helper.assertTrue(positions.all { helper.level.getBlockState(it).`is`(Blocks.STONE) }, "denied conversion changed blocks")
        helper.succeed()
    }

    @GameTest(maxTicks = 1200)
    fun convertsBlocksTogetherOnlyWhenPlayerMayBuild(helper: GameTestHelper) {
        val origin = BlockPos(704, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        val stair = Blocks.OAK_STAIRS.defaultBlockState()
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), stair, 3)
        val positions = listOf(origin, origin.east())
        val denied = helper.makeMockServerPlayer(GameType.ADVENTURE) as ServerPlayer
        denied.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        helper.assertTrue(TumbleApi.convertAssembly(helper.level, positions, denied) == null, "restricted player converted blocks")
        helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE) && helper.level.getBlockState(origin.east()) == stair,
            "denied conversion changed the world")
        val allowed = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        allowed.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, positions, allowed) ?: return helper.fail("could not convert assembly")
        helper.assertTrue(helper.level.getBlockState(origin).isAir && helper.level.getBlockState(origin.east()).isAir,
            "assembly did not remove all source blocks")
        helper.succeedWhen {
            Thread.sleep(5)
            val snapshot = TumbleApi.snapshot(helper.level, id)
                ?: return@succeedWhen helper.assertTrue(false, "assembly body is not ready")
            helper.assertTrue(kotlin.math.abs(snapshot.mass - 1.4875f) < 0.02f, "assembly mass was incorrect")
        }
    }

    @GameTest(maxTicks = 1200)
    fun assemblyEntitySavesItsShapeAndCreatesOneBody(helper: GameTestHelper) {
        val origin = BlockPos(672, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        val stair = Blocks.OAK_STAIRS.defaultBlockState()
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), stair, 3)
        val definition = AssemblyDefinition.of(listOf(
            AssemblyBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), AssemblyBlock(BlockPos(1, 0, 0), stair)
        ))
        val structure = AssemblyGeometry.capture(helper.level, origin, definition)
        helper.level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), Blocks.AIR.defaultBlockState(), 3)
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        helper.assertTrue(helper.level.addFreshEntity(entity), "could not add assembly entity")
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        runtime.manager.entityLoaded(entity, helper.level)
        val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        entity.saveWithoutId(saved)
        val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
            ?: return helper.fail("could not recreate assembly entity")
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), saved.buildResult()))
        helper.assertTrue(restored.assembly?.definition?.blocks == definition.blocks, "assembly block states were not saved")
        helper.assertTrue(restored.assembly?.parts == structure.parts, "assembly collision was not saved")
        helper.assertTrue(restored.boundingBox.xsize > 1.9, "assembly bounds did not include both blocks")
        helper.succeedWhen {
            Thread.sleep(5)
            val network = runtime.manager.networkProps().firstOrNull { it.propId == entity.uuid }
                ?: return@succeedWhen helper.assertTrue(false, "assembly body is not ready")
            val snapshot = runtime.service.latestFrame(network.dimension).bodies[network.bodyId]
                ?: return@succeedWhen helper.assertTrue(false, "assembly snapshot is not available")
            helper.assertTrue(kotlin.math.abs(snapshot.mass - 1.4875f) < 0.02f, "runtime body lost its mixed materials")
        }
    }

    @GameTest
    fun capturesActualBlockShapesAndMaterialsIntoOneBody(helper: GameTestHelper) {
        val origin = BlockPos(640, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        val stair = Blocks.OAK_STAIRS.defaultBlockState()
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), stair, 3)
        val definition = AssemblyDefinition.of(listOf(
            AssemblyBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
            AssemblyBlock(BlockPos(1, 0, 0), stair)
        ))
        val captured = AssemblyGeometry.capture(helper.level, origin, definition)
        val encoded = AssemblyStructure.CODEC.encodeStart(JsonOps.INSTANCE, captured).result().orElseThrow()
        helper.level.setBlock(origin.east(), Blocks.AIR.defaultBlockState(), 3)
        val decoded = AssemblyStructure.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow()
        helper.assertTrue(decoded.definition.blocks == definition.blocks, "saved assembly lost its block states")
        val shape = decoded.shape
        helper.assertTrue(shape.boxes.size == 3, "stone and stairs did not produce their three collision boxes")
        helper.assertTrue(shape.boxes.any { it.box.min == PhysicsVector(0.5, -0.5, -0.5) &&
            it.box.max == PhysicsVector(1.5, 0.0, 0.5) }, "stair slab is not in the correct local position")
        PhysicsWorld().use { world ->
            val body = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), shape))
            val snapshot = world.snapshot(body) ?: return helper.fail("assembly body was not created")
            helper.assertTrue(kotlin.math.abs(snapshot.mass - 1.4875f) < 0.02f, "assembly did not use each block's density")
            helper.assertTrue(world.snapshots().size == 1, "assembly used more than one body")
        }
        encoded.asJsonObject.getAsJsonArray("shapes")[0].asJsonObject.addProperty("block", 2)
        helper.assertTrue(AssemblyStructure.CODEC.parse(JsonOps.INSTANCE, encoded).result().isEmpty, "invalid shape owner was accepted")
        helper.succeed()
    }

    @GameTest
    fun palettePreservesA250BlockStaircase(helper: GameTestHelper) {
        val stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST)
        val blocks = (0 until 250).map { index ->
            AssemblyBlock(BlockPos(index % 25, index / 25, 0), if (index % 2 == 0) stair else Blocks.STONE.defaultBlockState())
        }
        val assembly = AssemblyDefinition.of(blocks)
        helper.assertTrue(assembly.palette.size == 2, "repeated block states were not paletted")
        val encoded = AssemblyDefinition.CODEC.encodeStart(JsonOps.INSTANCE, assembly).result().orElseThrow()
        val decoded = AssemblyDefinition.CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow()
        helper.assertTrue(decoded.blocks == blocks, "assembly positions or block properties changed during the round trip")
        encoded.asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("state", 2)
        helper.assertTrue(AssemblyDefinition.CODEC.parse(JsonOps.INSTANCE, encoded).result().isEmpty, "invalid palette index was accepted")
        helper.succeed()
    }

    @GameTest
    fun rejectsInvalidAssemblyDefinitions(helper: GameTestHelper) {
        val block = AssemblyBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState())
        fun rejects(blocks: List<AssemblyBlock>) = try {
            AssemblyDefinition.of(blocks)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        helper.assertTrue(rejects(emptyList()), "empty assembly was accepted")
        helper.assertTrue(rejects(listOf(block, block)), "duplicate position was accepted")
        helper.assertTrue(rejects(listOf(AssemblyBlock(BlockPos.ZERO, Blocks.AIR.defaultBlockState()))), "air was accepted")
        helper.assertTrue(rejects((0..512).map { AssemblyBlock(BlockPos(it % 25, it / 25, 0), block.state) }), "oversized assembly was accepted")
        helper.assertTrue(rejects(listOf(block, AssemblyBlock(BlockPos(65, 0, 0), block.state))), "excessive span was accepted")
        val mutable = BlockPos.MutableBlockPos(1, 2, 3)
        val captured = AssemblyDefinition.of(listOf(AssemblyBlock(mutable, block.state)))
        mutable.set(9, 9, 9)
        helper.assertTrue(captured.blocks.single().offset == BlockPos(1, 2, 3), "assembly retained a mutable position")
        helper.succeed()
    }
}
