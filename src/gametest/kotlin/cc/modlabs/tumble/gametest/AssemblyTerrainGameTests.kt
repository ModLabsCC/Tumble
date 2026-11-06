// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.assembly.AssemblyBlock
import cc.modlabs.tumble.assembly.AssemblyCollisionBox
import cc.modlabs.tumble.assembly.AssemblyDefinition
import cc.modlabs.tumble.assembly.AssemblyStructure
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.TerrainSectionTracker
import cc.modlabs.tumble.physics.TerrainSectionKey
import cc.modlabs.tumble.physics.PhysicsAcknowledgement
import cc.modlabs.tumble.physics.PhysicsCommand
import cc.modlabs.tumble.physics.PhysicsSectionPosition
import cc.modlabs.tumble.physics.physicsDimensionId
import cc.modlabs.tumble.api.TumbleApi
import java.util.concurrent.ConcurrentLinkedQueue
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity
import net.minecraft.world.level.block.piston.MovingPistonBlock
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity

class AssemblyTerrainGameTests {
    @GameTest(maxTicks = 1600)
    fun openingShulkerInvalidatesTerrainBeforePhysicsMoves(helper: GameTestHelper) = checkDynamicShape(helper, true)

    @GameTest(maxTicks = 1600)
    fun retractingPistonInvalidatesTerrainBeforePhysicsMoves(helper: GameTestHelper) = checkDynamicShape(helper, false)

    private fun checkDynamicShape(helper: GameTestHelper, shulker: Boolean) {
        val level = helper.level
        val pos = BlockPos(if (shulker) 6824 else 6952, 56, 8)
        for (x in (pos.x shr 4) - 2..(pos.x shr 4) + 2) for (z in -2..2) {
            level.setChunkForced(x, z, true)
            level.getChunkAt(BlockPos(x shl 4, pos.y, z shl 4))
        }
        val stone = Blocks.STONE.defaultBlockState()
        val structure = AssemblyStructure.of(AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone))), listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, level).apply {
            setPos(pos.x + 3.5, pos.y + 0.5, pos.z + 0.5)
            initializeAssembly(structure)
        }
        helper.assertTrue(level.addFreshEntity(entity), "could not add assembly")
        val runtime = PhysicsServices.runtime(level.server)!!
        runtime.manager.entityLoaded(entity, level)
        var performed = false
        var failure: String? = null
        helper.succeedWhen {
            Thread.sleep(5)
            if (performed) return@succeedWhen helper.assertTrue(failure == null, failure ?: "dynamic shape check failed")
            helper.assertTrue(TumbleApi.status(level, entity.uuid) == TumbleApi.PropStatus.READY &&
                runtime.terrain.assemblyTerrainReady(level, entity), "assembly terrain not ready")
            val body = runtime.manager.networkProps().firstOrNull { it.propId == entity.uuid }?.bodyId
                ?: return@succeedWhen helper.assertTrue(false, "assembly body not available")
            performed = true
            try {
                val state = if (shulker) Blocks.SHULKER_BOX.defaultBlockState()
                    else Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, Direction.EAST)
                level.setBlock(pos, state, 3)
                val piston = if (shulker) null else PistonMovingBlockEntity(pos, state, stone, Direction.EAST, false, false).also {
                    level.setBlockEntity(it)
                }
                val box = if (shulker) level.getBlockEntity(pos) as? ShulkerBoxBlockEntity else null
                if (shulker) {
                    helper.assertTrue(box != null, "shulker block entity is missing")
                    requireNotNull(box).startOpen(helper.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE))
                    helper.assertTrue(box.triggerEvent(1, 1), "could not deliver the shulker open event")
                }
                runtime.terrain.primeForPositions(level, listOf(pos))
                val key = TerrainSectionKey(level.physicsDimensionId(), PhysicsSectionPosition(pos.x shr 4, pos.y shr 4, pos.z shr 4))
                @Suppress("UNCHECKED_CAST")
                val revisions = runtime.terrain.javaClass.getDeclaredField("terrainRevisions").apply { isAccessible = true }.get(runtime.terrain) as Map<TerrainSectionKey, Long>
                val revision = requireNotNull(revisions[key])
                val deadline = System.nanoTime() + 5_000_000_000L
                while (runtime.service.appliedTerrainRevision() < revision && System.nanoTime() < deadline) Thread.sleep(1)
                helper.assertTrue(runtime.service.appliedTerrainRevision() >= revision, "dynamic section was not installed")
                @Suppress("UNCHECKED_CAST")
                val acknowledgements = runtime.service.javaClass.getDeclaredField("acknowledgements").apply { isAccessible = true }.get(runtime.service) as ConcurrentLinkedQueue<PhysicsAcknowledgement>
                runtime.service.submit(PhysicsCommand.ResumeBody(level.physicsDimensionId(), body))
                while (runtime.service.latestFrame(level.physicsDimensionId()).bodies[body]?.suspended != false && System.nanoTime() < deadline) Thread.sleep(1)
                helper.assertTrue(runtime.service.latestFrame(level.physicsDimensionId()).bodies[body]?.suspended == false, "assembly did not resume after initial installation")
                acknowledgements.removeIf { it is PhysicsAcknowledgement.AssemblyTerrainBlocked && it.propId == entity.uuid }
                val beforeShape = state.getCollisionShape(level, pos).toAabbs()
                val lock = runtime.service.javaClass.getDeclaredField("stepBoundary").apply { isAccessible = true }.get(runtime.service) as java.util.concurrent.locks.ReentrantLock
                lock.lock()
                try {
                    val stepBeforeBacklog = runtime.service.latestFrame(level.physicsDimensionId()).physicsStep
                    repeat(600) { runtime.service.submit(PhysicsCommand.RemoveTerrainSection(level.physicsDimensionId(), PhysicsSectionPosition(1000 + it, 0, 0))) }
                    helper.assertTrue(runtime.service.debugState().queuedCommands >= 600, "collision test did not build its backlog")
                    helper.assertTrue(runtime.service.latestFrame(level.physicsDimensionId()).physicsStep == stepBeforeBacklog, "worker advanced while backlog was queued")
                    if (shulker) ShulkerBoxBlockEntity.tick(level, pos, state, requireNotNull(box))
                    else PistonMovingBlockEntity.tick(level, pos, state, requireNotNull(piston))
                    helper.assertTrue(level.getBlockState(pos) == state, "test changed the block state")
                    helper.assertTrue(state.getCollisionShape(level, pos).toAabbs() != beforeShape, "block entity did not change its collision shape")
                    @Suppress("UNCHECKED_CAST")
                    val synced = runtime.terrain.javaClass.getDeclaredField("synced").apply { isAccessible = true }.get(runtime.terrain) as Set<TerrainSectionKey>
                    helper.assertTrue(key !in synced, "dynamic collision section stayed marked installed")
                } finally { lock.unlock() }
                val stop = System.nanoTime() + 5_000_000_000L
                while (acknowledgements.none { it is PhysicsAcknowledgement.AssemblyTerrainBlocked && it.propId == entity.uuid } && System.nanoTime() < stop) Thread.sleep(1)
                helper.assertTrue(acknowledgements.any { it is PhysicsAcknowledgement.AssemblyTerrainBlocked && it.propId == entity.uuid },
                    "assembly moved without blocking for the changed dynamic shape")
            } catch (error: Throwable) {
                failure = error.message ?: error.javaClass.name
                throw error
            }
        }
    }

    @GameTest
    fun axialRotationDoesNotRequireDistantChunks(helper: GameTestHelper) {
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of((0..25).map { AssemblyBlock(BlockPos(it, 0, 0), stone) })
        val structure = AssemblyStructure.of(definition, (0..25).map { x ->
            AssemblyCollisionBox(x, PhysicsBox(PhysicsVector(x - 0.5, -0.5, -0.5), PhysicsVector(x + 0.5, 0.5, 0.5)))
        })
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(0.5, 50.0, 8.5)
        assembly.initializeAssembly(structure)
        val tracker = TerrainSectionTracker(PhysicsServices.runtime(helper.level.server)!!.service)
        helper.assertTrue(tracker.coverageWithinBudget(assembly), "stationary coverage exceeded the budget")
        val stationary = tracker.coveredChunks(assembly)
        PhysicsPropEntity::class.java.getDeclaredField("angularVelocity").apply { isAccessible = true }
            .set(assembly, PhysicsVector(20.0, 0.0, 0.0))
        helper.assertTrue(tracker.coverageWithinBudget(assembly), "axial coverage exceeded the budget")
        helper.assertTrue(tracker.coveredChunks(assembly) == stationary, "axial rotation inflated coverage beyond the safety envelope")
        helper.succeed()
    }

    @GameTest
    fun rotatingAssemblyCoversItsDistantTipPath(helper: GameTestHelper) {
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of((0..50).map { AssemblyBlock(BlockPos(it, 0, 0), stone) })
        val structure = AssemblyStructure.of(definition, (0..50).map { x ->
            AssemblyCollisionBox(x, PhysicsBox(PhysicsVector(x - 0.5, -0.5, -0.5), PhysicsVector(x + 0.5, 0.5, 0.5)))
        })
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(0.5, 50.0, 8.5)
        assembly.initializeAssembly(structure)
        val tracker = TerrainSectionTracker(PhysicsServices.runtime(helper.level.server)!!.service)
        val tipDestination = ChunkPos.pack(3, -1)
        helper.assertTrue(tipDestination in tracker.coveredChunks(assembly), "stationary safety envelope misses the potential tip path")
        val velocity = PhysicsPropEntity::class.java.getDeclaredField("angularVelocity").apply { isAccessible = true }
        velocity.set(assembly, PhysicsVector(0.0, 2.0, 0.0))
        helper.assertTrue(tipDestination in tracker.coveredChunks(assembly), "rotating tip path is not covered")
        helper.succeed()
    }
}
