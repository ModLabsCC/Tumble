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
import cc.modlabs.tumble.physics.PhysicsCommand
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
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks

class ConnectionCrashGameTests {
    private val origin = BlockPos(6509, 70, 0)
    private val ids = Path.of("connection-crash-ids.txt")

    @GameTest(maxTicks = 1600)
    fun inject(helper: GameTestHelper) {
        if (System.getProperty("tumble.connection.phase") != "inject") return helper.succeed()
        val level = helper.level
        for (position in listOf(origin, origin.offset(4, 0, 0))) {
            level.setChunkForced(position.x shr 4, position.z shr 4, true)
            level.getChunkAt(position)
        }
        for (x in -1..5) for (z in -1..1) level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val structure = AssemblyStructure.of(AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone))), listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val root = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, level).apply {
            setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
            initializeAssembly(structure)
        }
        helper.assertTrue(level.addFreshEntity(root), "could not add baseline assembly")
        val runtime = PhysicsServices.runtime(level.server)!!
        runtime.manager.entityLoaded(root, level)
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 2.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        var peer: PhysicsPropEntity? = null
        helper.succeedWhen {
            Thread.sleep(5)
            when (phase) {
                0 -> {
                    if (TumbleApi.status(level, root.uuid) != TumbleApi.PropStatus.READY)
                        return@succeedWhen helper.assertTrue(false, "baseline assembly not ready")
                    level.save(null, true, false)
                    peer = runtime.manager.spawn(level, PhysicsVector(origin.x + 4.5, origin.y + 0.5, origin.z + 0.5))
                        ?: return@succeedWhen helper.fail("could not spawn unsaved endpoint")
                    Files.writeString(ids, "${root.uuid}\n${requireNotNull(peer).uuid}\n")
                    phase = 1
                }
                1 -> {
                    val other = requireNotNull(peer)
                    if (TumbleApi.status(level, other.uuid) != TumbleApi.PropStatus.READY)
                        return@succeedWhen helper.assertTrue(false, "unsaved endpoint not ready")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, root) == ToolGunWeldResult.SELECTED, "could not select root")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, other) == ToolGunWeldResult.WELDED, "could not queue weld")
                    phase = 2
                }
            }
            helper.assertTrue(false, "waiting for connection intent crash, phase=$phase")
        }
    }

    @GameTest(maxTicks = 1600)
    fun verify(helper: GameTestHelper) {
        if (System.getProperty("tumble.connection.phase") != "verify") return helper.succeed()
        val level = helper.level
        for (position in listOf(origin, origin.offset(4, 0, 0))) {
            level.setChunkForced(position.x shr 4, position.z shr 4, true)
            level.getChunkAt(position)
            level.waitForEntities(ChunkPos(position.x shr 4, position.z shr 4), 0)
        }
        val stored = Files.readAllLines(ids).map(UUID::fromString)
        val rootId = stored[0]
        val peerId = stored[1]
        val runtime = PhysicsServices.runtime(level.server)!!
        val dimension = level.physicsDimensionId()
        var phase = 0
        var startX = 0.0
        var checkpoint = 0L
        helper.succeedWhen {
            Thread.sleep(5)
            val root = level.getEntity(rootId) as? PhysicsPropEntity ?: return@succeedWhen helper.assertTrue(false, "saved assembly missing after crash")
            val peer = level.getEntity(peerId) as? PhysicsPropEntity ?: return@succeedWhen helper.assertTrue(false, "unsaved endpoint missing after crash")
            when (phase) {
                0 -> {
                    if (TumbleApi.status(level, rootId) != TumbleApi.PropStatus.READY || TumbleApi.status(level, peerId) != TumbleApi.PropStatus.READY)
                        return@succeedWhen helper.assertTrue(false, "crash-recovered group not ready")
                    val peerBody = runtime.manager.networkProps().first { it.propId == peerId }
                    startX = root.x
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, peerBody.bodyId, PhysicsVector(40.0, 0.0, 0.0)))
                    checkpoint = runtime.service.latestFrame(dimension).physicsStep
                    phase = 1
                }
                1 -> if (runtime.service.latestFrame(dimension).physicsStep - checkpoint >= 20) {
                    helper.assertTrue(root.x > startX + 0.1, "crash-recovered native weld did not move the assembly")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for recovered link, phase=$phase")
        }
    }
}
