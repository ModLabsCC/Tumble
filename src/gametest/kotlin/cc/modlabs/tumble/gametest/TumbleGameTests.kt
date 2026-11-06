// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.TumbleGameRules
import cc.modlabs.tumble.TumbleMod
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
import cc.modlabs.tumble.physics.PhysicsAcknowledgement
import cc.modlabs.tumble.physics.PhysicsCommand
import cc.modlabs.tumble.physics.TumbleTuningKey
import kotlin.math.abs
import java.util.concurrent.ConcurrentLinkedQueue
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.GameType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.entity.PersistentEntitySectionManager
import net.minecraft.world.level.entity.Visibility
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

class TumbleGameTests {
    @GameTest
    fun stoppingServerRetainsPropsAfterPhysicsRuntimeDetaches(helper: GameTestHelper) {
        if (System.getProperty("tumble.isolated.test") != "shutdown") return helper.succeed()
        val level = helper.level
        val server = level.server
        val fields = PhysicsServices.javaClass
        val runtimesField = fields.getDeclaredField("runtimes").apply { isAccessible = true }
        val stoppingField = fields.getDeclaredField("stopping").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val runtimes = runtimesField.get(PhysicsServices) as MutableMap<net.minecraft.server.MinecraftServer, PhysicsServices.Runtime>
        @Suppress("UNCHECKED_CAST")
        val stopping = stoppingField.get(PhysicsServices) as MutableSet<net.minecraft.server.MinecraftServer>
        val runtime = requireNotNull(runtimes.remove(server))
        val prop = cc.modlabs.tumble.entity.PhysicsPropEntity(cc.modlabs.tumble.entity.TumbleEntities.PHYSICS_PROP, level)
        try {
            helper.assertTrue(PhysicsServices.beforePropRemoval(prop, level), "a server that never started Tumble was blocked")
            runtimes[server] = runtime
            val detach = fields.getDeclaredMethod("detachForShutdown", net.minecraft.server.MinecraftServer::class.java)
                .apply { isAccessible = true }
            helper.assertTrue(detach.invoke(PhysicsServices, server) === runtime, "shutdown did not detach the runtime")
            prop.discard()
            helper.assertTrue(!prop.isRemoved, "discard bypassed the shutdown guard after the runtime detached")
            prop.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED)
            helper.assertTrue(!prop.isRemoved, "kill bypassed the shutdown guard after the runtime detached")
            helper.assertTrue(!PhysicsServices.canTeleportProp(prop, level, level), "shutdown allowed a prop teleport")
        } finally {
            stopping.remove(server)
            runtimes[server] = runtime
        }
        helper.assertTrue(PhysicsServices.beforePropRemoval(prop, level), "guard remained active after shutdown ended")
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun addonCanObserveBodyCreationFailure(helper: GameTestHelper) {
        val baseX = 512
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val id = TumbleApi.spawn(helper.level, net.minecraft.world.phys.Vec3(baseX + 1.5, 3.0, 1.5), Blocks.STONE.defaultBlockState())
            ?: return helper.fail("could not spawn prop")
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
        acknowledgements.add(PhysicsAcknowledgement.BodyCreationFailed(id, "injected test failure", body.requestId))
        runtime.manager.tick()
        helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.FAILED, "addon could not observe failure")
        helper.assertTrue(TumbleApi.remove(helper.level, id), "failed prop could not be removed")
        helper.assertTrue(runtime.service.debugState().state == PhysicsServiceState.RUNNING, "physics service stopped")
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun pendingDroppedItemSurvivesChunkVisibilityChanges(helper: GameTestHelper) {
        val baseX = 448
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val field = net.minecraft.server.level.ServerLevel::class.java.getDeclaredField("entityManager")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entities = field.get(helper.level) as PersistentEntitySectionManager<Entity>
        val chunk = ChunkPos(baseX / 16, 0)
        entities.updateChunkStatus(chunk, Visibility.TICKING)
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val rule = TumbleGameRules.CONVERT_DROPPED_BLOCKS
        val previous = helper.level.gameRules.get(rule)
        helper.level.gameRules.set(rule, true, helper.level.server)
        try {
            val item = ItemEntity(helper.level, baseX + 1.5, 2.0, 1.5, ItemStack(Items.STONE))
            helper.level.addFreshEntity(item)
            manager.itemLoaded(item, helper.level)
            manager.tick()
        } finally {
            helper.level.gameRules.set(rule, previous, helper.level.server)
        }
        val area = net.minecraft.world.phys.AABB(baseX.toDouble(), -16.0, 0.0, baseX + 4.0, 16.0, 4.0)
        val prop = helper.level.getEntitiesOfClass(PhysicsPropEntity::class.java, area).singleOrNull()
            ?: return helper.fail("dropped item did not become a prop")
        val registeredBefore = manager.registeredPropCount()
        entities.updateChunkStatus(chunk, Visibility.HIDDEN)
        val registeredWhileHidden = manager.registeredPropCount()
        entities.updateChunkStatus(chunk, Visibility.TICKING)
        helper.assertTrue(registeredWhileHidden == registeredBefore - 1, "hiding the chunk did not unload the prop")
        helper.assertTrue(helper.level.getEntitiesOfClass(ItemEntity::class.java, area).none { it.isAlive }, "unloading duplicated the item")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(manager.networkProps().any { it.propId == prop.uuid }, "visible prop has no physics body")
        }
    }

    @GameTest(maxTicks = 600)
    fun pendingConversionSurvivesChunkVisibilityChanges(helper: GameTestHelper) {
        val baseX = 416
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val field = net.minecraft.server.level.ServerLevel::class.java.getDeclaredField("entityManager")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val entities = field.get(helper.level) as PersistentEntitySectionManager<Entity>
        val chunk = ChunkPos(baseX / 16, 0)
        entities.updateChunkStatus(chunk, Visibility.TICKING)
        val position = BlockPos(baseX + 1, 1, 1)
        helper.level.setBlock(position, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE).apply { setPos(baseX + 1.5, 1.0, 3.0) }
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val prop = manager.convertBlock(helper.level, position, player as net.minecraft.server.level.ServerPlayer)
            ?: return helper.fail("could not convert block")
        val registeredBefore = manager.registeredPropCount()
        entities.updateChunkStatus(chunk, Visibility.HIDDEN)
        val remainedAir = helper.level.getBlockState(position).isAir
        val registeredWhileHidden = manager.registeredPropCount()
        entities.updateChunkStatus(chunk, Visibility.TICKING)
        helper.assertTrue(remainedAir, "hiding the chunk restored the block alongside the prop")
        helper.assertTrue(registeredWhileHidden == registeredBefore - 1, "hiding the chunk did not unload the prop")
        helper.assertTrue(manager.registeredPropCount() == registeredBefore, "showing the chunk did not reload the prop")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(manager.networkProps().any { it.propId == prop.uuid }, "visible prop has no physics body")
            helper.assertTrue(helper.level.getBlockState(position).isAir, "visibility change duplicated the block")
        }
    }

    @GameTest(maxTicks = 600)
    fun pendingConversionSurvivesEntityUnloadWithoutDuplicatingBlock(helper: GameTestHelper) {
        val baseX = 384
        prepareChunk(helper, baseX / 16)
        clearArena(helper, baseX)
        val position = BlockPos(baseX + 1, 1, 1)
        helper.level.setBlock(position, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE).apply { setPos(baseX + 1.5, 1.0, 3.0) }
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val prop = manager.convertBlock(helper.level, position, player as net.minecraft.server.level.ServerPlayer)
            ?: return helper.fail("could not convert block")
        val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
        prop.saveWithoutId(saved)
        prop.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
        PhysicsServices.entityUnloaded(prop, helper.level)
        helper.assertTrue(helper.level.getBlockState(position).isAir, "unloading restored the block alongside the saved prop")
        val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
            ?: return helper.fail("could not recreate saved prop")
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), saved.buildResult()))
        helper.assertTrue(helper.level.addFreshEntity(restored), "could not reload saved prop")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(manager.networkProps().any { it.propId == restored.uuid }, "reloaded prop has no physics body")
            helper.assertTrue(helper.level.getBlockState(position).isAir, "conversion left a duplicate block")
        }
    }

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
        var invalidatedOnRecreation = false
        val cacheField = runtime.manager.javaClass.getDeclaredField("submersionByProp").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val waterCache = cacheField.get(runtime.manager) as Map<java.util.UUID, Float>
        helper.succeedWhen {
            Thread.sleep(5)
            val networkProp = runtime.manager.networkProps().firstOrNull { it.propId == prop.uuid }
                ?: return@succeedWhen helper.assertTrue(false, "physics body is not ready")
            val step = runtime.service.latestFrame(networkProp.dimension).physicsStep
            when (phase) {
                0 -> {
                    helper.assertTrue(runtime.manager.usePhysicsGunSecondary(player, prop, false), "could not freeze prop")
                    helper.assertTrue(runtime.service.submit(PhysicsCommand.SetTransform(
                        networkProp.dimension, networkProp.bodyId, PhysicsTransform(PhysicsVector(baseX + 1.5, 2.0, 1.5))
                    )), "could not reset the frozen prop")
                    oldBody = networkProp.bodyId.value
                    checkpoint = step
                    phase = 1
                }
                1 -> if (step - checkpoint >= 60) {
                    helper.assertTrue(MinecraftTerrainAdapter.submersion(helper.level, prop.boundingBox) == 1f,
                        "frozen prop did not settle fully underwater: y=${prop.y}")
                    helper.assertTrue(waterCache[prop.uuid] == 1f, "water state was not cached before recreation")
                    repeat(4) { runtime.manager.cycleToolGunMode(player, 1) }
                    helper.assertTrue(runtime.manager.useToolGun(player, prop) == ToolGunResult.APPLIED, "could not change preset")
                    invalidatedOnRecreation = prop.uuid !in waterCache
                    phase = 2
                }
                2 -> {
                    helper.assertTrue(invalidatedOnRecreation, "old body's water state survived recreation")
                    if (networkProp.bodyId.value != oldBody) {
                        checkpoint = step
                        phase = 3
                    }
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
                    helper.assertTrue(prop.y > releaseY + 0.5,
                        "recreated prop did not float: releaseY=$releaseY, y=${prop.y}, " +
                            "water=${MinecraftTerrainAdapter.submersion(helper.level, prop.boundingBox)}, " +
                            "snapshot=${runtime.service.latestFrame(networkProp.dimension).bodies[networkProp.bodyId]}")
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
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
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
            TumbleItems.TOOL_GUN.use(helper.level, player, InteractionHand.MAIN_HAND)
            helper.assertTrue(!manager.undoToolGunWeld(player), "using the Tool Gun did not undo the weld")
        }
    }

    @GameTest
    fun developerToolsHaveNoDefaultRecipesOrAdvancements(helper: GameTestHelper) {
        val server = helper.level.server
        for (name in listOf("gravity_gun", "physics_gun", "tool_gun")) {
            val recipe = ResourceKey.create(Registries.RECIPE, TumbleMod.id(name))
            helper.assertTrue(server.recipeManager.byKey(recipe).isEmpty, "$name is still craftable")
            helper.assertTrue(server.advancements.get(TumbleMod.id("recipes/$name")) == null, "$name recipe unlock still exists")
        }
        for (name in listOf("root", "gravity_gun", "physics_gun", "first_prop", "first_weld", "living_grab", "tool_mode", "tool_action")) {
            helper.assertTrue(server.advancements.get(TumbleMod.id(name)) == null, "$name advancement still exists")
        }
        helper.succeed()
    }

    @GameTest
    fun developerToolsRemainAvailableToCreatorsAndOperators(helper: GameTestHelper) {
        val tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(TumbleMod.id("main"))
            ?: return helper.fail("Tumble creative tab is missing")
        tab.buildContents(CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, true, helper.level.registryAccess()))
        val player = helper.makeMockServerPlayer(GameType.SURVIVAL) as net.minecraft.server.level.ServerPlayer
        val source = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput()
        for ((name, item) in listOf("gravitygun" to TumbleItems.GRAVITY_GUN, "physicsgun" to TumbleItems.PHYSICS_GUN, "toolgun" to TumbleItems.TOOL_GUN)) {
            helper.assertTrue(tab.contains(ItemStack(item)), "$name is missing from the creative tab")
            helper.level.server.commands.dispatcher.execute("tumble $name", source)
            helper.assertTrue(player.inventory.contains(ItemStack(item)), "$name operator command did not grant the item")
        }
        helper.succeed()
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
