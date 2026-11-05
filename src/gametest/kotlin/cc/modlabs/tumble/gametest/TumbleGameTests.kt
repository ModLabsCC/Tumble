// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.TumbleGameRules
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
import cc.modlabs.tumble.physics.ToolGunResult
import cc.modlabs.tumble.physics.PhysicsServiceState
import cc.modlabs.tumble.physics.TumbleTuningKey
import kotlin.math.abs
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

class TumbleGameTests {
    @GameTest(maxTicks = 3000)
    fun recreatedSubmergedBodyReceivesWaterState(helper: GameTestHelper) {
        val baseX = 352
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        for (x in 0..2) for (z in 0..2) {
            helper.level.setBlock(BlockPos(baseX + x, 0, z), Blocks.STONE.defaultBlockState(), 3)
            for (y in 1..3) helper.level.setBlock(BlockPos(baseX + x, y, z), Blocks.WATER.defaultBlockState(), 3)
        }
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val prop = runtime.manager.spawn(helper.level, PhysicsVector(baseX + 1.5, 2.0, 1.5), Blocks.OAK_LOG.defaultBlockState(),
            angularVelocity = PhysicsVector.ZERO) ?: return helper.fail("could not spawn prop")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 2.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        var checkpoint = 0L
        var oldBody = 0L
        var releaseY = 0.0
        helper.succeedWhen {
            Thread.sleep(5)
            val networkProp = runtime.manager.networkProps().firstOrNull { it.propId == prop.uuid }
                ?: return@succeedWhen helper.assertTrue(false, "physics body is not ready")
            val step = runtime.service.latestFrame(networkProp.dimension).physicsStep
            when (phase) {
                0 -> {
                    helper.assertTrue(runtime.manager.usePhysicsGunSecondary(player, prop, false), "could not freeze prop")
                    oldBody = networkProp.bodyId.value
                    checkpoint = step
                    phase = 1
                }
                1 -> if (step - checkpoint >= 60) {
                    repeat(4) { runtime.manager.cycleToolGunMode(player, 1) }
                    helper.assertTrue(runtime.manager.useToolGun(player, prop) == ToolGunResult.APPLIED, "could not change preset")
                    phase = 2
                }
                2 -> if (networkProp.bodyId.value != oldBody) {
                    checkpoint = step
                    phase = 3
                }
                3 -> if (step - checkpoint >= 60) {
                    player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
                    helper.assertTrue(runtime.manager.togglePhysicsGunHeld(player, prop), "could not unfreeze prop")
                    runtime.manager.releasePlayer(player.uuid)
                    releaseY = prop.y
                    checkpoint = step
                    phase = 4
                }
                4 -> if (step - checkpoint >= 120) {
                    helper.assertTrue(prop.y > releaseY + 0.5, "recreated prop did not float")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for water state, phase=$phase")
        }
    }

    @GameTest(maxTicks = 600)
    fun toolGunRejectsRopeBetweenOverlappingProps(helper: GameTestHelper) {
        val baseX = 320
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        for (x in 0..3) for (z in 0..3) helper.level.setBlock(BlockPos(baseX + x, 0, z), Blocks.STONE.defaultBlockState(), 3)
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val first = runtime.manager.spawn(helper.level, PhysicsVector(baseX + 1.5, 2.0, 1.5), Blocks.STONE.defaultBlockState())
            ?: return helper.fail("could not spawn first prop")
        val second = runtime.manager.spawn(helper.level, PhysicsVector(baseX + 2.5, 2.0, 1.5), Blocks.STONE.defaultBlockState())
            ?: return helper.fail("could not spawn second prop")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 2.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        runtime.manager.cycleToolGunMode(player, 1)
        helper.succeedWhen {
            Thread.sleep(5)
            val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
            helper.assertTrue(first.uuid in ready && second.uuid in ready, "physics bodies are not ready")
            helper.assertTrue(runtime.manager.useToolGun(player, first) == ToolGunResult.SELECTED, "could not select first prop")
            second.setPos(first.x, first.y, first.z)
            helper.assertTrue(runtime.manager.useToolGun(player, second) == ToolGunResult.FAILED, "zero-length rope was accepted")
            helper.assertTrue(runtime.service.debugState().state == PhysicsServiceState.RUNNING, "physics service stopped")
        }
    }

    @GameTest(maxTicks = 600)
    fun convertingSupportUpdatesAttachedBlocks(helper: GameTestHelper) {
        val baseX = 288
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val support = BlockPos(baseX + 1, 1, 1)
        helper.level.setBlock(support.below(), Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(support, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(support.above(), Blocks.TORCH.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 1.0, 3.0)
        }
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val prop = manager.convertBlock(helper.level, support, player as net.minecraft.server.level.ServerPlayer)
            ?: return helper.fail("could not convert the support block")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(manager.networkProps().any { it.propId == prop.uuid }, "physics body is not ready: removed=${prop.isRemoved}, position=${prop.position()}, block=${helper.level.getBlockState(support)}")
            helper.assertTrue(helper.level.getBlockState(support.above()).isAir, "the unsupported torch did not update")
        }
    }

    @GameTest(maxTicks = 600)
    fun holdingLivingEntityDoesNotChangeSavedGravity(helper: GameTestHelper) {
        val baseX = 256
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        helper.level.setBlock(BlockPos(baseX + 1, 2, 4), Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 1.6, 1.5)
            yRot = 0f
            xRot = 0f
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.GRAVITY_GUN))
        }
        val pig = EntityTypes.PIG.create(helper.level, EntitySpawnReason.COMMAND)
            ?: return helper.fail("Could not create test pig")
        pig.setPos(baseX + 1.5, 3.0, 4.0)
        pig.setNoAi(true)
        helper.level.addFreshEntity(pig)
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        helper.succeedWhen {
            helper.assertTrue(manager.toggleHeld(player), "could not pick up the pig")
            val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
            pig.saveWithoutId(output)
            val restored = EntityTypes.PIG.create(helper.level, EntitySpawnReason.COMMAND)
                ?: return@succeedWhen helper.fail("Could not create restored pig")
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), output.buildResult()))
            helper.assertTrue(manager.launchHeld(player) && pig.deltaMovement.z > 1.0, "test did not hold the expected pig")
            helper.assertTrue(!restored.isNoGravity, "holding a mob made no-gravity persist in its save")
            pig.setNoGravity(true)
            helper.assertTrue(manager.toggleHeld(player), "could not hold the no-gravity pig")
            manager.releasePlayer(player.uuid)
            helper.assertTrue(pig.isNoGravity, "holding changed a mob's existing no-gravity setting")
            pig.discard()
        }
    }

    @GameTest(maxTicks = 600)
    fun physicsGunHandlesSmallConfiguredGrabDistance(helper: GameTestHelper) {
        val baseX = 224
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val previous = manager.tuningValue(TumbleTuningKey.GRAB_DISTANCE)
        helper.assertTrue(manager.setTuning(TumbleTuningKey.GRAB_DISTANCE, 0.1), "could not set grab distance")
        helper.runBeforeTestEnd { manager.setTuning(TumbleTuningKey.GRAB_DISTANCE, previous) }
        val prop = manager.spawn(helper.level, PhysicsVector(baseX + 1.5, 3.0, 1.5),
            Blocks.STONE.defaultBlockState(), angularVelocity = PhysicsVector.ZERO)
            ?: return helper.fail("Could not create a test prop")
        val player = helper.makeMockServerPlayer(GameType.CREATIVE).apply {
            setPos(baseX + 1.5, 3.0, 3.0)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.PHYSICS_GUN))
        }
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(manager.networkProps().any { it.propId == prop.uuid }, "physics body is not ready")
            helper.assertTrue(manager.togglePhysicsGunHeld(player, prop), "could not grab the prop")
            manager.setTuning(TumbleTuningKey.GRAB_DISTANCE, previous)
            prop.discard()
        }
    }

    @GameTest(maxTicks = 600)
    fun convertingDroppedStackKeepsRemainingItems(helper: GameTestHelper) {
        val baseX = 192
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val level = helper.level
        for (x in 0..3) for (z in 0..3) level.setBlock(BlockPos(baseX + x, 0, z), Blocks.STONE.defaultBlockState(), 3)
        val rule = TumbleGameRules.CONVERT_DROPPED_BLOCKS
        val previous = level.gameRules.get(rule)
        level.gameRules.set(rule, true, level.server)
        val item = ItemEntity(level, baseX + 1.5, 3.0, 1.5, ItemStack(Items.STONE, 64))
        level.addFreshEntity(item)
        val manager = PhysicsServices.runtime(level.server)!!.manager
        helper.runBeforeTestEnd { level.gameRules.set(rule, previous, level.server) }
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(item.isAlive, "the remaining dropped stack disappeared")
            helper.assertTrue(item.item.count == 63, "expected 63 remaining blocks, got ${item.item.count}")
            val props = level.getEntitiesOfClass(PhysicsPropEntity::class.java, net.minecraft.world.phys.AABB(
                baseX.toDouble(), -16.0, 0.0, baseX + 4.0, 16.0, 4.0
            ))
            helper.assertTrue(props.any { prop -> manager.networkProps().any { it.propId == prop.uuid } }, "converted prop was not created")
            level.gameRules.set(rule, previous, level.server)
            item.discard()
            props.forEach { it.discard() }
        }
    }

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
            if (physicsStep - readyAt > 900) {
                return@succeedWhen helper.fail(
                    "water test did not settle after 900 physics steps: y=${prop.y}, drainedAt=$drainedAt, " +
                        "water=${MinecraftTerrainAdapter.submersion(helper.level, prop.boundingBox)}, " +
                        "snapshot=${runtime.service.latestFrame(networkProp.dimension).bodies[networkProp.bodyId]}"
                )
            }
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
        helper.level.getChunkAt(BlockPos(chunkX * 16, 0, 0))
    }

    private fun clearArena(helper: GameTestHelper, baseX: Int) {
        for (x in baseX - 1..baseX + 4) for (y in 0..8) for (z in -1..4) {
            helper.level.setBlock(BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3)
        }
    }
}
