// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.physics.MinecraftTerrainAdapter
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.PhysicsWorld
import cc.modlabs.tumble.physics.PhysicsBodyDefinition
import cc.modlabs.tumble.physics.PhysicsBuoyancy
import cc.modlabs.tumble.physics.PhysicsSectionPosition
import cc.modlabs.tumble.physics.PhysicsTransform
import cc.modlabs.tumble.physics.ToolGunWeldResult
import kotlin.math.abs
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

class TumbleGameTests {
    @GameTest
    fun extractedTerrainAllowsSubmergedLogsToRise(helper: GameTestHelper) {
        val baseX = 160
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        for (x in 0..2) for (z in 0..2) {
            helper.level.setBlock(BlockPos(baseX + x, 0, z), Blocks.STONE.defaultBlockState(), 3)
            for (y in 1..3) helper.level.setBlock(BlockPos(baseX + x, y, z), Blocks.WATER.defaultBlockState(), 3)
        }
        PhysicsWorld().use { world ->
            world.setBuoyancyForceScale(1.5f)
            world.syncTerrain(MinecraftTerrainAdapter.extractSection(helper.level, PhysicsSectionPosition(baseX / 16, 0, 0)))
            val body = world.createBody(PhysicsBodyDefinition(
                PhysicsTransform(PhysicsVector(baseX + 1.5, 2.0, 1.5)),
                buoyancy = PhysicsBuoyancy(true, 1.8f, 0.18f, 0.18f, 0.08f)
            ))
            world.setSubmersion(body, 1f)
            repeat(180) { world.step() }
            helper.assertTrue(world.snapshot(body)!!.transform.position.y > 3.0, "extracted terrain trapped the submerged log")
        }
        helper.succeed()
    }

    @GameTest
    fun savedPropsKeepBlockStateProperties(helper: GameTestHelper) {
        val state = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH)
        val prop = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        prop.initialize(state, PhysicsVector.ZERO)
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        prop.saveWithoutId(output)
        val restored = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), output.buildResult()))
        helper.assertTrue(restored.blockState == state, "saved prop lost its block state properties")

        val oldSave = output.buildResult().copy()
        oldSave.remove("TumbleBlockState")
        val legacy = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        legacy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), oldSave))
        helper.assertTrue(legacy.blockState == Blocks.OAK_STAIRS.stateDefinition.any(), "old prop saves no longer load")
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun physicsPropCreatesRuntimeBodyAndSnapshot(helper: GameTestHelper) {
        val baseX = 0
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val runtime = PhysicsServices.runtime(helper.level.server)
            ?: return helper.fail("Tumble physics runtime is unavailable")
        val prop = runtime.manager.spawn(
            helper.level,
            PhysicsVector(baseX + 1.5, 4.0, 1.5),
            Blocks.OAK_LOG.defaultBlockState(),
            angularVelocity = PhysicsVector.ZERO
        ) ?: return helper.fail("physics prop did not spawn")
        helper.runBeforeTestEnd { prop.discard() }

        helper.succeedWhen {
            Thread.sleep(5)
            val networkProp = runtime.manager.networkProps().firstOrNull { it.propId == prop.uuid }
                ?: return@succeedWhen helper.assertTrue(false, "physics body is not ready: prop=${prop.uuid}, removed=${prop.isRemoved}, position=${prop.position()}")
            val snapshot = runtime.service.latestFrame(networkProp.dimension).bodies[networkProp.bodyId]
            helper.assertTrue(snapshot != null, "physics snapshot is not available")
            val position = snapshot!!.transform.position
            helper.assertTrue(
                position.x.isFinite() && position.y.isFinite() && position.z.isFinite(),
                "physics snapshot position is not finite"
            )
        }
    }

    @GameTest(maxTicks = 8000)
    fun oakLogFloatsOnlyWhileItsBoundsTouchWater(helper: GameTestHelper) {
        val baseX = 32
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        for (x in 0..2) for (z in 0..2) {
            helper.level.setBlock(BlockPos(baseX + x, 0, z), Blocks.STONE.defaultBlockState(), 3)
            for (y in 1..3) helper.level.setBlock(BlockPos(baseX + x, y, z), Blocks.WATER.defaultBlockState(), 3)
        }
        val prop = PhysicsServices.runtime(helper.level.server)?.manager?.spawn(
            helper.level,
            PhysicsVector(baseX + 1.5, 2.0, 1.5),
            Blocks.OAK_LOG.defaultBlockState(),
            angularVelocity = PhysicsVector.ZERO
        ) ?: return helper.fail("Tumble physics runtime is unavailable")
        helper.runBeforeTestEnd { prop.discard() }
        val runtime = PhysicsServices.runtime(helper.level.server)
            ?: return helper.fail("Tumble physics runtime is unavailable")
        var bodyReadyAt: Long? = null
        var drainedAt: Double? = null

        helper.succeedWhen {
            Thread.sleep(5)
            val networkProp = runtime.manager.networkProps().firstOrNull { it.propId == prop.uuid }
                ?: return@succeedWhen helper.assertTrue(false, "physics body is not ready: prop=${prop.uuid}, removed=${prop.isRemoved}, position=${prop.position()}")
            val physicsStep = runtime.service.latestFrame(networkProp.dimension).physicsStep
            val readyAt = bodyReadyAt ?: physicsStep.also { bodyReadyAt = it }
            helper.assertTrue(physicsStep - readyAt >= 300, "waiting for 300 physics steps")
            val beforeDrain = drainedAt
            if (beforeDrain != null) {
                helper.assertTrue(prop.y < beforeDrain - 0.25, "oak log did not fall after water was removed")
                return@succeedWhen
            }
            val submerged = MinecraftTerrainAdapter.submersion(helper.level, prop.boundingBox)
            helper.assertTrue(submerged > 0f, "floating prop no longer touches water")
            helper.assertTrue(
                prop.y in 3.0..4.1,
                "oak log did not float: y=${prop.y}, water=$submerged, velocity=${prop.deltaMovement}, " +
                    "snapshot=${runtime.service.latestFrame(networkProp.dimension).bodies[networkProp.bodyId]}, " +
                    "waterResponse=${MinecraftTerrainAdapter.buoyancy(prop.blockState)}, service=${runtime.service.debugState()}"
            )
            helper.assertTrue(abs(prop.deltaMovement.y) < 0.05, "oak log is still bouncing")
            drainedAt = prop.y
            for (x in 0..2) for (z in 0..2) for (y in 1..3) {
                helper.level.setBlock(BlockPos(baseX + x, y, z), Blocks.AIR.defaultBlockState(), 3)
            }
            helper.assertTrue(false, "waiting for the log to fall")
        }
    }

    @GameTest(maxTicks = 600)
    fun toolGunWeldsTwoRuntimeProps(helper: GameTestHelper) {
        val baseX = 64
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        for (x in 0..3) for (z in 0..3) {
            helper.level.setBlock(BlockPos(baseX + x, 0, z), Blocks.STONE.defaultBlockState(), 3)
        }
        val manager = PhysicsServices.runtime(helper.level.server)?.manager
            ?: return helper.fail("Tumble physics runtime is unavailable")
        val first = manager.spawn(
            helper.level, PhysicsVector(baseX + 1.5, 1.5, 1.5), Blocks.OAK_LOG.defaultBlockState(),
            angularVelocity = PhysicsVector.ZERO
        ) ?: return helper.fail("first prop did not spawn")
        val second = manager.spawn(
            helper.level, PhysicsVector(baseX + 2.5, 1.5, 1.5), Blocks.OAK_LOG.defaultBlockState(),
            angularVelocity = PhysicsVector.ZERO
        ) ?: return helper.fail("second prop did not spawn")
        helper.runBeforeTestEnd { first.discard(); second.discard() }
        val player = helper.makeMockServerPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 1.0, 4.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }

        helper.succeedWhen {
            Thread.sleep(5)
            val ready = manager.networkProps().mapTo(mutableSetOf()) { it.propId }
            helper.assertTrue(first.uuid in ready && second.uuid in ready, "physics bodies are not ready")
            helper.assertTrue(
                manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED,
                "first prop was not selected"
            )
            helper.assertTrue(
                manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED,
                "second prop was not welded"
            )
        }
    }

    @GameTest
    fun physicsGunCannotConvertBlocksInAdventureMode(helper: GameTestHelper) {
        val baseX = 96
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val pos = BlockPos(baseX + 1, 1, 1)
        helper.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.ADVENTURE).apply {
            setPos(baseX + 1.5, 1.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
        }
        val hit = net.minecraft.world.phys.BlockHitResult(
            net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false
        )
        TumbleItems.PHYSICS_GUN.useOn(net.minecraft.world.item.context.UseOnContext(
            player, net.minecraft.world.InteractionHand.MAIN_HAND, hit
        ))
        helper.assertTrue(helper.level.getBlockState(pos).`is`(Blocks.STONE), "Adventure player converted a block")
        val creative = helper.makeMockServerPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 1.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
        }
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val prop = manager.convertBlock(helper.level, pos, creative as net.minecraft.server.level.ServerPlayer)
        helper.assertTrue(prop != null && helper.level.getBlockState(pos).isAir, "Creative control could not convert the block")
        helper.runBeforeTestEnd { prop?.discard() }
        helper.assertTrue(manager.togglePhysicsGunHeld(creative, prop!!), "Conversion did not begin holding the prop")
        helper.succeed()
    }

    @GameTest
    fun physicsGunHonorsBlockBreakCancellation(helper: GameTestHelper) {
        val baseX = 112
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val pos = BlockPos(baseX + 1, 1, 1)
        helper.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 1.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
        }
        var checked = false
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { _, actor, block, _, _ ->
            if (actor.uuid == player.uuid && block == pos) { checked = true; false } else true
        }
        val hit = net.minecraft.world.phys.BlockHitResult(
            net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false
        )
        TumbleItems.PHYSICS_GUN.useOn(net.minecraft.world.item.context.UseOnContext(
            player, net.minecraft.world.InteractionHand.MAIN_HAND, hit
        ))
        helper.assertTrue(checked, "Block-break protection was not consulted")
        helper.assertTrue(helper.level.getBlockState(pos).`is`(Blocks.STONE), "Protected block was converted")
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun physicsGunCannotPlaceBlocksInAdventureMode(helper: GameTestHelper) {
        val baseX = 128
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val prop = manager.spawn(helper.level, PhysicsVector(baseX + 1.5, 3.0, 1.5),
            Blocks.STONE.defaultBlockState(), angularVelocity = PhysicsVector.ZERO)
            ?: return helper.fail("Could not create a test prop")
        helper.runBeforeTestEnd { prop.discard() }
        val player = helper.makeMockServerPlayer(GameType.ADVENTURE).apply {
            setPos(baseX + 1.5, 3.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
        }
        var result: Boolean? = null
        helper.succeedWhen {
            Thread.sleep(5)
            if (result == null) {
                helper.assertTrue(manager.networkProps().any { it.propId == prop.uuid }, "physics body is not ready")
                result = manager.usePhysicsGunSecondary(player, prop, true)
            }
            helper.assertTrue(result == false, "Adventure player placed a block")
            helper.assertTrue(!prop.isRemoved, "Protected prop was removed")
            val creative = helper.makeMockServerPlayer(GameType.CREATIVE).apply {
                setPos(prop.x, prop.y, prop.z + 1.0)
                setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
            }
            helper.assertTrue(manager.usePhysicsGunSecondary(creative, prop, true), "Creative control could not place the block")
        }
    }

    private fun prepareChunk(helper: GameTestHelper, chunkX: Int) {
        helper.level.setChunkForced(chunkX, 0, true)
        helper.runBeforeTestEnd { helper.level.setChunkForced(chunkX, 0, false) }
        helper.level.getChunkAt(BlockPos(chunkX * 16, 0, 0))
    }

    private fun clearArena(helper: GameTestHelper, baseX: Int) {
        for (x in baseX - 1..baseX + 4) for (y in 0..8) for (z in -1..4) {
            helper.level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3)
        }
    }
}
