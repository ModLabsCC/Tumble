// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.gametest.PlacementCrashHook
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsCommand
import cc.modlabs.tumble.physics.PhysicsMotionType
import cc.modlabs.tumble.physics.physicsDimensionId
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.ToolGunWeldResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

class PlacementRestartGameTests {
    private val origin = BlockPos(7008, 80, 0)
    private val idFile = Path.of("placement-crash-ids.txt")

    @GameTest(maxTicks = 1200)
    fun inject(helper: GameTestHelper) {
        if (System.getProperty("tumble.placement.phase") != "inject") return helper.succeed()
        val level = helper.level
        for (dx in -1..1) for (dz in -1..1) {
            val cx = (origin.x shr 4) + dx
            val cz = (origin.z shr 4) + dz
            level.setChunkForced(cx, cz, true)
            level.getChunk(cx, cz)
            level.waitForEntities(net.minecraft.world.level.ChunkPos(cx, cz), 0)
        }
        for (x in -3..5) for (z in -3..3) level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        val manager = PhysicsServices.runtime(level.server)!!.manager
        val first = manager.spawn(level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5), Blocks.STONE.defaultBlockState())
            ?: return helper.fail("could not spawn first prop")
        val second = manager.spawn(level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5), Blocks.OAK_PLANKS.defaultBlockState())
            ?: return helper.fail("could not spawn second prop")
        val player = (helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        helper.succeedWhen {
            Thread.sleep(5)
            when (phase) {
                0 -> {
                    val ready = manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    helper.assertTrue(first.uuid in ready && second.uuid in ready, "props not ready")
                    helper.assertTrue(manager.usePhysicsGunSecondary(player, first, false), "first freeze failed")
                    helper.assertTrue(manager.usePhysicsGunSecondary(player, second, false), "second freeze failed")
                    helper.assertTrue(manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "selection failed")
                    helper.assertTrue(manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld failed")
                    phase = 1
                }
                1 -> {
                    helper.assertTrue(TumbleApi.status(level, first.uuid) == TumbleApi.PropStatus.READY, "weld not ready")
                    val pos = first.blockPosition()
                    player.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 2.0)
                    Files.writeString(idFile, "${first.uuid}\n${second.uuid}\n${pos.x} ${pos.y} ${pos.z}\n")
                    for (dx in -1..1) for (dz in -1..1) level.setChunkForced((origin.x shr 4) + dx, (origin.z shr 4) + dz, false)
                    PlacementCrashHook.level = level
                    manager.usePhysicsGunSecondary(player, first, true)
                    helper.fail("placement did not reach the crash hook")
                }
            }
            helper.assertTrue(false, "waiting for placement crash")
        }
    }

    @GameTest(maxTicks = 1200)
    fun verify(helper: GameTestHelper) {
        if (System.getProperty("tumble.placement.phase") != "verify") return helper.succeed()
        val lines = Files.readAllLines(idFile)
        val first = UUID.fromString(lines[0])
        val second = UUID.fromString(lines[1])
        val xyz = lines[2].split(" ").map(String::toInt)
        val pos = BlockPos(xyz[0], xyz[1], xyz[2])
        val level = helper.level
        helper.assertTrue(!level.forceLoadedChunks.contains(net.minecraft.world.level.ChunkPos(pos.x shr 4, pos.z shr 4).pack()), "replay leaked a forced chunk")
        for (dx in -1..1) for (dz in -1..1) {
            val cx = (origin.x shr 4) + dx
            val cz = (origin.z shr 4) + dz
            level.setChunkForced(cx, cz, true)
            level.getChunk(cx, cz)
            level.waitForEntities(net.minecraft.world.level.ChunkPos(cx, cz), 0)
        }
        var motionPhase = 0
        var beforeX = 0.0
        var startStep = 0L
        helper.succeedWhen {
            Thread.sleep(5)
            val props = level.getEntitiesOfClass(PhysicsPropEntity::class.java, AABB(
                origin.x - 32.0, origin.y - 80.0, origin.z - 32.0,
                origin.x + 32.0, origin.y + 32.0, origin.z + 32.0))
            val ids = props.mapTo(mutableSetOf()) { it.uuid }
            val committed = System.getProperty("tumble.placement.stage") in setOf("started", "removed")
            helper.assertTrue(second in ids, "peer was lost: $ids")
            helper.assertTrue(if (committed) first !in ids && level.getBlockState(pos).`is`(Blocks.STONE)
                else first in ids && level.getBlockState(pos).isAir, "placement lost or duplicated prop: $ids ${level.getBlockState(pos)}")
            val manager = PhysicsServices.runtime(level.server)!!.manager
            val journal = manager.javaClass.getDeclaredField("journal").apply { isAccessible = true }.get(manager)
            val records = journal.javaClass.getMethod("records").invoke(journal) as List<*>
            val linked = records.filterNotNull().any { record ->
                val type = record.javaClass
                setOf(type.getMethod("getFirst").invoke(record), type.getMethod("getSecond").invoke(record)) == setOf(first, second) &&
                    type.getMethod("getDeleted").invoke(record) == false
            }
            helper.assertTrue(linked != committed, "link did not recover with placement")
            if (!committed) {
                val runtime = PhysicsServices.runtime(level.server)!!
                val dimension = level.physicsDimensionId()
                val bodies = manager.networkProps().associateBy { it.propId }
                val firstBody = bodies[first] ?: return@succeedWhen helper.assertTrue(false, "restored first body not ready")
                val secondBody = bodies[second] ?: return@succeedWhen helper.assertTrue(false, "restored second body not ready")
                val firstProp = props.single { it.uuid == first }
                if (motionPhase == 0) {
                    beforeX = firstProp.x
                    runtime.service.submit(PhysicsCommand.SetMotionType(dimension, firstBody.bodyId, PhysicsMotionType.DYNAMIC))
                    runtime.service.submit(PhysicsCommand.SetMotionType(dimension, secondBody.bodyId, PhysicsMotionType.DYNAMIC))
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, secondBody.bodyId, PhysicsVector(40.0, 0.0, 0.0)))
                    startStep = runtime.service.latestFrame(dimension).physicsStep
                    motionPhase = 1
                    return@succeedWhen helper.assertTrue(false, "waiting for native weld motion")
                }
                helper.assertTrue(runtime.service.latestFrame(dimension).physicsStep - startStep >= 20, "waiting for native weld step")
                helper.assertTrue(firstProp.x > beforeX + 0.1, "restored native weld did not move the first prop: $beforeX -> ${firstProp.x}")
            }
        }
    }
}
