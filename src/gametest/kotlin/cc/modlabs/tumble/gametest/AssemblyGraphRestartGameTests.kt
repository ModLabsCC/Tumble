// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.assembly.AssemblyBlock
import cc.modlabs.tumble.assembly.AssemblyCollisionBox
import cc.modlabs.tumble.assembly.AssemblyDefinition
import cc.modlabs.tumble.assembly.AssemblyStructure
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.ToolGunWeldResult
import cc.modlabs.tumble.physics.physicsDimensionId
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks

/** Two separate server processes share a saved world and connection journal. */
class AssemblyGraphRestartGameTests {
    private val origin = BlockPos(4304, 80, 0)
    private val idFile = Path.of("assembly-graph-restart-ids.txt")

    private fun structure(oversized: Boolean): AssemblyStructure {
        val stone = Blocks.STONE.defaultBlockState()
        val positions = if (oversized) listOf(BlockPos(-32, -32, -32), BlockPos(32, 32, 32)) else listOf(BlockPos.ZERO)
        return AssemblyStructure.of(AssemblyDefinition.of(positions.map { AssemblyBlock(it, stone) }), positions.mapIndexed { index, p ->
            AssemblyCollisionBox(index, PhysicsBox(PhysicsVector(p.x - 0.5, p.y - 0.5, p.z - 0.5),
                PhysicsVector(p.x + 0.5, p.y + 0.5, p.z + 0.5)))
        })
    }

    private fun loadNearby(helper: GameTestHelper) {
        for (x in (origin.x shr 4) - 2..(origin.x shr 4) + 2) for (z in -2..2) {
            helper.level.setChunkForced(x, z, true)
            helper.level.getChunkAt(BlockPos(x shl 4, origin.y, z shl 4))
        }
    }

    @GameTest(maxTicks = 1600)
    fun prepare(helper: GameTestHelper) {
        if (System.getProperty("tumble.graph.phase") != "prepare") return helper.succeed()
        val level = helper.level
        loadNearby(helper)
        for (x in -3..4) for (z in -3..3) level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, level).apply {
            setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
            initializeAssembly(structure(false))
        }
        helper.assertTrue(level.addFreshEntity(assembly), "could not add assembly")
        val runtime = PhysicsServices.runtime(level.server)!!
        runtime.manager.entityLoaded(assembly, level)
        val peer = runtime.manager.spawn(level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn peer")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        helper.succeedWhen {
            Thread.sleep(5)
            when (phase) {
                0 -> {
                    helper.assertTrue(TumbleApi.status(level, assembly.uuid) == TumbleApi.PropStatus.READY &&
                        TumbleApi.status(level, peer.uuid) == TumbleApi.PropStatus.READY, "bodies not ready")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, assembly) == ToolGunWeldResult.SELECTED, "selection failed")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, peer) == ToolGunWeldResult.WELDED, "weld failed")
                    phase = 1
                }
                1 -> {
                    helper.assertTrue(TumbleApi.status(level, assembly.uuid) == TumbleApi.PropStatus.READY &&
                        TumbleApi.status(level, peer.uuid) == TumbleApi.PropStatus.READY, "weld not installed")
                    PhysicsServices.chunkUnloaded(level, origin.x shr 4, 0)
                    phase = 2
                }
                2 -> {
                    helper.assertTrue(runtime.manager.networkProps().none { it.propId == assembly.uuid || it.propId == peer.uuid }, "component did not park")
                    val parking = runtime.manager.javaClass.getDeclaredField("parkingGroups").apply { isAccessible = true }
                    helper.assertTrue((parking.get(runtime.manager) as Map<*, *>).isEmpty(), "component park was not acknowledged")
                    assembly.initializeAssembly(structure(true))
                    Files.writeString(idFile, "${assembly.uuid}\n${peer.uuid}\n")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for graph restart setup, phase=$phase")
        }
    }

    @GameTest(maxTicks = 1600)
    fun verify(helper: GameTestHelper) {
        if (System.getProperty("tumble.graph.phase") != "verify") return helper.succeed()
        val ids = Files.readAllLines(idFile).map(UUID::fromString)
        val level = helper.level
        loadNearby(helper)
        val runtime = PhysicsServices.runtime(level.server)!!
        val dimension = level.physicsDimensionId()
        var checkpoint = -1L
        var released = false
        helper.succeedWhen {
            Thread.sleep(5)
            val assembly = level.getEntity(ids[0]) as? PhysicsPropEntity
                ?: return@succeedWhen helper.assertTrue(false, "saved assembly not loaded")
            val peer = level.getEntity(ids[1]) as? PhysicsPropEntity
                ?: return@succeedWhen helper.assertTrue(false, "saved peer not loaded")
            if (!released) {
                helper.assertTrue(assembly.assembly?.definition?.blocks?.size == 2, "over-budget assembly was not saved")
                if (checkpoint < 0) checkpoint = runtime.service.latestFrame(dimension).physicsStep
                if (runtime.service.latestFrame(dimension).physicsStep - checkpoint < 30)
                    return@succeedWhen helper.assertTrue(false, "waiting for persisted graph gate")
                helper.assertTrue(runtime.manager.networkProps().none { it.propId == assembly.uuid || it.propId == peer.uuid },
                    "over-budget component escaped after restart")
                assembly.initializeAssembly(structure(false))
                released = true
            }
            helper.assertTrue(TumbleApi.status(level, assembly.uuid) == TumbleApi.PropStatus.READY &&
                TumbleApi.status(level, peer.uuid) == TumbleApi.PropStatus.READY, "bounded component did not resume")
        }
    }
}
