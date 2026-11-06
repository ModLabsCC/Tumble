// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.assembly.AssemblyBlock
import cc.modlabs.tumble.assembly.AssemblyCollisionBox
import cc.modlabs.tumble.assembly.AssemblyDefinition
import cc.modlabs.tumble.assembly.AssemblyStructure
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.physics.*
import java.util.concurrent.ConcurrentLinkedQueue
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.commands.data.BlockDataAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.piston.MovingPistonBlock
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity

class PistonLoadGameTests {
    @GameTest(maxTicks = 1600)
    fun dataCommandInvalidatesMovingPistonBeforePhysicsMoves(helper: GameTestHelper) {
        val level = helper.level
        val pos = BlockPos(7208, 56, 8)
        for (x in (pos.x shr 4) - 2..(pos.x shr 4) + 2) for (z in -2..2) {
            level.setChunkForced(x, z, true)
            level.getChunkAt(BlockPos(x shl 4, pos.y, z shl 4))
        }
        val stone = Blocks.STONE.defaultBlockState()
        val structure = AssemblyStructure.of(AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone))), listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val prop = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, level).apply {
            setPos(pos.x + 3.5, pos.y + 0.5, pos.z + 0.5)
            initializeAssembly(structure)
        }
        helper.assertTrue(level.addFreshEntity(prop), "could not add assembly")
        val runtime = PhysicsServices.runtime(level.server)!!
        runtime.manager.entityLoaded(prop, level)
        var performed = false
        var failure: String? = null
        helper.succeedWhen {
            Thread.sleep(5)
            if (performed) return@succeedWhen helper.assertTrue(failure == null, failure ?: "load test failed")
            helper.assertTrue(TumbleApi.status(level, prop.uuid) == TumbleApi.PropStatus.READY &&
                runtime.terrain.assemblyTerrainReady(level, prop), "assembly terrain not ready")
            val body = runtime.manager.networkProps().firstOrNull { it.propId == prop.uuid }?.bodyId
                ?: return@succeedWhen helper.assertTrue(false, "assembly body missing")
            performed = true
            try {
                val state = Blocks.MOVING_PISTON.defaultBlockState().setValue(MovingPistonBlock.FACING, Direction.EAST)
                level.setBlock(pos, state, 3)
                val piston = PistonMovingBlockEntity(pos, state, stone, Direction.EAST, false, false)
                level.setBlockEntity(piston)
                runtime.terrain.primeForPositions(level, listOf(pos))
                val key = TerrainSectionKey(level.physicsDimensionId(), PhysicsSectionPosition(pos.x shr 4, pos.y shr 4, pos.z shr 4))
                @Suppress("UNCHECKED_CAST")
                val revisions = runtime.terrain.javaClass.getDeclaredField("terrainRevisions").apply { isAccessible = true }.get(runtime.terrain) as Map<TerrainSectionKey, Long>
                val revision = requireNotNull(revisions[key])
                val deadline = System.nanoTime() + 5_000_000_000L
                while (runtime.service.appliedTerrainRevision() < revision && System.nanoTime() < deadline) Thread.sleep(1)
                helper.assertTrue(runtime.service.appliedTerrainRevision() >= revision, "piston terrain not installed")
                @Suppress("UNCHECKED_CAST")
                val acknowledgements = runtime.service.javaClass.getDeclaredField("acknowledgements").apply { isAccessible = true }.get(runtime.service) as ConcurrentLinkedQueue<PhysicsAcknowledgement>
                runtime.service.submit(PhysicsCommand.ResumeBody(level.physicsDimensionId(), body))
                while (runtime.service.latestFrame(level.physicsDimensionId()).bodies[body]?.suspended != false && System.nanoTime() < deadline) Thread.sleep(1)
                helper.assertTrue(runtime.service.latestFrame(level.physicsDimensionId()).bodies[body]?.suspended == false, "assembly did not resume")
                acknowledgements.removeIf { it is PhysicsAcknowledgement.AssemblyTerrainBlocked && it.propId == prop.uuid }
                val shape = state.getCollisionShape(level, pos).toAabbs()
                val accessor = BlockDataAccessor(piston, pos)
                val data = accessor.data
                data.putFloat("progress", 0.5f)
                accessor.setData(data)
                helper.assertTrue(level.getBlockState(pos) == state, "data edit changed block state")
                helper.assertTrue(state.getCollisionShape(level, pos).toAabbs() != shape, "data edit did not change collision shape")
                @Suppress("UNCHECKED_CAST")
                val synced = runtime.terrain.javaClass.getDeclaredField("synced").apply { isAccessible = true }.get(runtime.terrain) as Set<TerrainSectionKey>
                helper.assertTrue(key !in synced, "data edit left stale terrain marked installed")
                val stop = System.nanoTime() + 5_000_000_000L
                while (acknowledgements.none { it is PhysicsAcknowledgement.AssemblyTerrainBlocked && it.propId == prop.uuid } && System.nanoTime() < stop) Thread.sleep(1)
                helper.assertTrue(acknowledgements.any { it is PhysicsAcknowledgement.AssemblyTerrainBlocked && it.propId == prop.uuid }, "worker did not block assembly for data edit")
            } catch (error: Throwable) {
                failure = error.message ?: error.javaClass.name
                throw error
            }
        }
    }
}
