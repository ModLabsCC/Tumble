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
import cc.modlabs.tumble.physics.ToolGunMode
import cc.modlabs.tumble.physics.ToolGunResult
import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.network.AssemblyPayload
import com.mojang.serialization.JsonOps
import io.netty.buffer.Unpooled
import java.util.UUID
import java.nio.file.Files
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
import net.minecraft.world.level.storage.LevelResource

class AssemblyGameTests {
    @GameTest
    fun clearRetainsPropsWhenConnectionTombstonesFail(helper: GameTestHelper) {
        val logger = LoggerFactory.getLogger("clear-connection-failure-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        val directory = Files.createTempDirectory("tumble-clear-failure")
        val path = directory.resolve("connections.dat")
        val blocked = directory.resolve("blocked")
        Files.createDirectory(blocked)
        Files.writeString(blocked.resolve("keep"), "keep")
        try {
            val first = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
            val second = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
            val journalClass = Class.forName("cc.modlabs.tumble.physics.DurableConnectionJournal")
            val journal = journalClass.getConstructor(java.nio.file.Path::class.java).newInstance(path)
            val recordClass = Class.forName("cc.modlabs.tumble.physics.DurableConnection")
            val record = recordClass.getConstructor(UUID::class.java, String::class.java, UUID::class.java, UUID::class.java,
                UUID::class.java, cc.modlabs.tumble.physics.PhysicsJointKind::class.java,
                cc.modlabs.tumble.physics.PhysicsJointFrames::class.java, Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, List::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(UUID.randomUUID(), helper.level.physicsDimensionId().value, first.uuid, second.uuid,
                    UUID.randomUUID(), cc.modlabs.tumble.physics.PhysicsJointKind.NO_COLLIDE, null, 0f, false, emptyList<Any>(), true)
            journalClass.getMethod("put", recordClass).invoke(journal, record)
            val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning())
            manager.javaClass.getDeclaredField("journal").apply { isAccessible = true }.set(manager, journal)
            manager.entityLoaded(first, helper.level)
            manager.entityLoaded(second, helper.level)
            val journalPath = journalClass.getDeclaredField("path").apply { isAccessible = true }
            journalPath.set(journal, blocked)
            helper.assertTrue(manager.clear(helper.level) == 0, "clear reported props removed after journal failure")
            helper.assertTrue(!first.isRemoved && !second.isRemoved, "clear discarded props after journal failure")
            journalPath.set(journal, path)
            helper.assertTrue(manager.clear(helper.level) == 2, "clear did not remove props after journal recovered")
        } finally {
            service.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(blocked.resolve("keep"))
            Files.deleteIfExists(blocked)
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }

    @GameTest(maxTicks = 400)
    fun connectedPropPlacementCompletes(helper: GameTestHelper) {
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val origin = helper.absolutePos(BlockPos(1, 3, 1))
        val first = manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        val second = manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn second prop")
        val player = (helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        helper.succeedWhen {
            Thread.sleep(5)
            if (phase == 0) {
                val ready = manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                helper.assertTrue(first.uuid in ready && second.uuid in ready, "bodies are not ready")
                helper.assertTrue(manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "selection failed")
                helper.assertTrue(manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld failed")
                phase = 1
                helper.assertTrue(false, "waiting for weld")
            }
            helper.assertTrue(TumbleApi.status(helper.level, first.uuid) == TumbleApi.PropStatus.READY, "weld not ready")
            val position = first.blockPosition()
            player.setPos(position.x + 0.5, position.y.toDouble(), position.z + 2.0)
            helper.assertTrue(manager.usePhysicsGunSecondary(player, first, true), "connected prop did not become block")
            helper.assertTrue(first.isRemoved && !second.isRemoved && helper.level.getBlockState(position) == first.blockState,
                "placement did not preserve the block and peer")
            val journal = helper.level.server.getWorldPath(LevelResource.ROOT).resolve("tumble-prop-placement-journal").resolve("${first.uuid}.journal")
            helper.assertTrue(!Files.exists(journal), "completed placement retained its journal")
        }
    }

    @GameTest(maxTicks = 400)
    fun failedPlacementJournalKeepsLinkedProps(helper: GameTestHelper) {
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        val origin = helper.absolutePos(BlockPos(1, 3, 1))
        val first = manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        val second = manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn second prop")
        val player = (helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        helper.succeedWhen {
            Thread.sleep(5)
            if (phase == 0) {
                val ready = manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                helper.assertTrue(first.uuid in ready && second.uuid in ready, "bodies are not ready")
                helper.assertTrue(manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "selection failed")
                helper.assertTrue(manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld failed")
                phase = 1
                helper.assertTrue(false, "waiting for weld")
            }
            helper.assertTrue(TumbleApi.status(helper.level, first.uuid) == TumbleApi.PropStatus.READY, "weld not ready")
            val position = first.blockPosition()
            player.setPos(position.x + 0.5, position.y.toDouble(), position.z + 2.0)
            val dir = Files.createTempDirectory("tumble-placement-failure")
            val obstruction = dir.resolve("blocked")
            Files.writeString(obstruction, "obstruction")
            val journal = manager.javaClass.getDeclaredField("placementJournal").apply { isAccessible = true }.get(manager)
            val field = journal.javaClass.getDeclaredField("directory").apply { isAccessible = true }
            val original = field.get(journal)
            try {
                field.set(journal, obstruction)
                helper.assertTrue(!manager.usePhysicsGunSecondary(player, first, true), "placement ignored journal failure")
                helper.assertTrue(!first.isRemoved && !second.isRemoved && helper.level.getBlockState(position).isAir, "failure consumed a prop or wrote a block")
                val connections = manager.javaClass.getDeclaredField("journal").apply { isAccessible = true }.get(manager)
                val records = connections.javaClass.getMethod("records").invoke(connections) as List<*>
                helper.assertTrue(records.filterNotNull().any { record ->
                    val type = record.javaClass
                    setOf(type.getMethod("getFirst").invoke(record), type.getMethod("getSecond").invoke(record)) == setOf(first.uuid, second.uuid) &&
                        type.getMethod("getDeleted").invoke(record) == false
                }, "journal failure removed the weld")
            } finally {
                field.set(journal, original)
                Files.deleteIfExists(obstruction)
                Files.deleteIfExists(dir)
            }
        }
    }

    @GameTest(maxTicks = 400)
    fun deniedBlockPlacementKeepsExistingWeld(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 3, 1))
        val first = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        val second = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn second prop")
        val player = (helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        var blocker: UUID? = null
        helper.succeedWhen {
            Thread.sleep(5)
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    helper.assertTrue(first.uuid in ready && second.uuid in ready, "bodies are not ready")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "weld selection failed")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld creation failed")
                    phase = 1
                }
                1 -> {
                    helper.assertTrue(TumbleApi.status(helper.level, first.uuid) == TumbleApi.PropStatus.READY, "weld not installed")
                    val position = first.blockPosition()
                    player.setPos(position.x + 0.5, position.y.toDouble(), position.z + 2.0)
                    helper.assertTrue(helper.level.setBlock(position, Blocks.STONE.defaultBlockState(), 3), "could not place fence source")
                    blocker = TumbleApi.convertAssembly(helper.level, listOf(position), player)
                        ?: return@succeedWhen helper.fail("could not fence placement target")
                    helper.assertTrue(TumbleApi.status(helper.level, first.uuid) == TumbleApi.PropStatus.READY,
                        "linked prop became unavailable before placement attempt")
                    helper.assertTrue(!runtime.manager.usePhysicsGunSecondary(player, first, true), "protected block placement succeeded")
                    helper.assertTrue(!first.isRemoved && helper.level.getBlockState(position).isAir, "failed placement consumed the prop")
                    val journal = runtime.manager.javaClass.getDeclaredField("journal").apply { isAccessible = true }.get(runtime.manager)
                    val records = journal.javaClass.getMethod("records").invoke(journal) as List<*>
                    val retained = records.filterNotNull().any { record ->
                        val type = record.javaClass
                        val endpoints = setOf(type.getMethod("getFirst").invoke(record), type.getMethod("getSecond").invoke(record))
                        endpoints == setOf(first.uuid, second.uuid) && type.getMethod("getDeleted").invoke(record) == false
                    }
                    helper.assertTrue(retained, "failed placement removed the durable weld")
                    phase = 2
                }
                2 -> {
                    val blockId = requireNotNull(blocker)
                    helper.assertTrue(TumbleApi.status(helper.level, blockId) == TumbleApi.PropStatus.READY, "blocking conversion not complete")
                    TumbleApi.remove(helper.level, blockId)
                    runtime.manager.entityUnloaded(first, helper.level)
                    runtime.manager.entityUnloaded(second, helper.level)
                    first.discard()
                    second.discard()
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for placement-denial test")
        }
    }

    @GameTest(maxTicks = 400)
    fun failedConnectionTombstoneDoesNotRemoveProp(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 3, 1))
        val first = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        val second = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn second prop")
        val player = (helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        var phase = 0
        helper.succeedWhen {
            Thread.sleep(5)
            if (phase == 0) {
                val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                helper.assertTrue(first.uuid in ready && second.uuid in ready, "bodies are not ready")
                helper.assertTrue(runtime.manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "weld selection failed")
                helper.assertTrue(runtime.manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld creation failed")
                phase = 1
                return@succeedWhen helper.assertTrue(false, "waiting for weld")
            }
            helper.assertTrue(TumbleApi.status(helper.level, first.uuid) == TumbleApi.PropStatus.READY, "weld not installed")
            val journal = runtime.manager.javaClass.getDeclaredField("journal").apply { isAccessible = true }.get(runtime.manager)
            val path = journal.javaClass.getDeclaredField("path").apply { isAccessible = true }
            val original = path.get(journal)
            val directory = Files.createTempDirectory("tumble-removal-failure")
            val obstruction = directory.resolve("journal")
            Files.createDirectory(obstruction)
            Files.writeString(obstruction.resolve("keep"), "keep")
            try {
                path.set(journal, obstruction)
                helper.assertTrue(!TumbleApi.remove(helper.level, first.uuid), "API reported removal despite failed tombstone")
                helper.assertTrue(!first.isRemoved && TumbleApi.status(helper.level, first.uuid) != TumbleApi.PropStatus.UNAVAILABLE,
                    "failed tombstone removed prop")
                val position = first.blockPosition()
                helper.assertTrue(helper.level.getBlockState(position).isAir, "expected an empty block conversion target")
                helper.assertTrue(!runtime.manager.usePhysicsGunSecondary(player, first, true), "block conversion ignored tombstone failure")
                helper.assertTrue(helper.level.getBlockState(position).isAir && !first.isRemoved, "failed tombstone duplicated the prop as a block")
                repeat(ToolGunMode.REMOVER.ordinal) { runtime.manager.cycleToolGunMode(player, 1) }
                helper.assertTrue(runtime.manager.useToolGun(player, first) == ToolGunResult.FAILED, "secondary remover ignored tombstone failure")
                helper.assertTrue(!runtime.manager.useToolGunPrimary(player, first), "primary remover ignored tombstone failure")
                helper.assertTrue(!first.isRemoved, "failed remover discarded the prop")
                val nether = helper.level.server.getLevel(net.minecraft.world.level.Level.NETHER)
                    ?: return@succeedWhen helper.fail("nether not available")
                val transition = net.minecraft.world.level.portal.TeleportTransition(nether,
                    net.minecraft.world.phys.Vec3(0.5, 80.0, 0.5), net.minecraft.world.phys.Vec3.ZERO,
                    0f, 0f, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING)
                helper.assertTrue(first.teleport(transition) == null && !first.isRemoved && first.level() === helper.level,
                    "cross-dimension teleport cloned a linked prop after failed tombstone")
                first.discard()
                helper.assertTrue(!first.isRemoved, "direct discard ignored tombstone failure")
                first.initialize(Blocks.TNT.defaultBlockState(), PhysicsVector.ZERO)
                val body = runtime.manager.networkProps().first { it.propId == first.uuid }.bodyId
                val impacts = runtime.service.javaClass.getDeclaredField("impacts").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val queue = impacts.get(runtime.service) as ConcurrentLinkedQueue<cc.modlabs.tumble.physics.PhysicsImpactNotification>
                val count = runtime.service.javaClass.getDeclaredField("impactCount").apply { isAccessible = true }
                    .get(runtime.service) as java.util.concurrent.atomic.AtomicInteger
                val rule = cc.modlabs.tumble.TumbleGameRules.EXPLODE_TNT_ON_IMPACT
                val previous = helper.level.gameRules.get(rule)
                try {
                    helper.level.gameRules.set(rule, true, helper.level.server)
                    count.incrementAndGet()
                    queue.add(cc.modlabs.tumble.physics.PhysicsImpactNotification(helper.level.physicsDimensionId(),
                        cc.modlabs.tumble.physics.PhysicsImpactEvent(body, null, PhysicsVector.ZERO, PhysicsVector(0.0, 1.0, 0.0), 30f)))
                    runtime.manager.tick()
                    helper.assertTrue(!first.isRemoved, "TNT detonation ignored tombstone failure")
                } finally {
                    helper.level.gameRules.set(rule, previous, helper.level.server)
                }
            } finally {
                path.set(journal, original)
                Files.deleteIfExists(obstruction.resolve("keep"))
                Files.deleteIfExists(obstruction)
                Files.deleteIfExists(directory)
            }
            runtime.manager.entityUnloaded(first, helper.level)
            runtime.manager.entityUnloaded(second, helper.level)
            first.discard()
            second.discard()
        }
    }

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

    @GameTest(maxTicks = 800)
    fun legacyConnectionWithoutPassengerProofRemainsParked(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 4, 1))
        fun prop(x: Double) = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level).apply {
            setPos(x, origin.y + 0.5, origin.z + 0.5)
            initialize(Blocks.STONE.defaultBlockState(), PhysicsVector.ZERO)
        }
        val first = prop(origin.x + 0.5)
        val second = prop(origin.x + 2.5)
        val stateClass = Class.forName("cc.modlabs.tumble.physics.DurableBodyState")
        val stateConstructor = stateClass.getConstructor(UUID::class.java, PhysicsTransform::class.java, PhysicsVector::class.java, PhysicsVector::class.java)
        val checkpoint = listOf(first, second).map { prop ->
            stateConstructor.newInstance(prop.uuid, PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z)), PhysicsVector.ZERO, PhysicsVector.ZERO)
        }
        val recordClass = Class.forName("cc.modlabs.tumble.physics.DurableConnection")
        val record = recordClass.getConstructor(UUID::class.java, String::class.java, UUID::class.java, UUID::class.java,
            UUID::class.java, cc.modlabs.tumble.physics.PhysicsJointKind::class.java, cc.modlabs.tumble.physics.PhysicsJointFrames::class.java,
            Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, java.util.List::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(UUID.randomUUID(), helper.level.physicsDimensionId().value, first.uuid, second.uuid, UUID.randomUUID(),
                cc.modlabs.tumble.physics.PhysicsJointKind.NO_COLLIDE, null, 0f, false, checkpoint, false)
        val journal = runtime.manager.javaClass.getDeclaredField("journal").apply { isAccessible = true }.get(runtime.manager)
        journal.javaClass.getMethod("put", recordClass).invoke(journal, record)
        runtime.manager.entityLoaded(first, helper.level)
        runtime.manager.entityLoaded(second, helper.level)
        val marker = net.minecraft.world.entity.item.ItemEntity(helper.level, first.x, first.y, first.z, ItemStack(net.minecraft.world.item.Items.STONE))
        helper.assertTrue(marker.startRiding(first, true, false), "legacy passenger relationship could not load")
        val dimension = helper.level.physicsDimensionId()
        val start = runtime.service.latestFrame(dimension).physicsStep
        helper.succeedWhen {
            Thread.sleep(5)
            if (runtime.service.latestFrame(dimension).physicsStep - start < 30) return@succeedWhen helper.assertTrue(false, "waiting for legacy recovery gate")
            val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
            helper.assertTrue(first.uuid !in ready && second.uuid !in ready, "legacy passenger-unsafe graph was activated")
            helper.assertTrue(marker.isPassenger, "legacy passenger relationship was discarded")
            marker.stopRiding()
            runtime.manager.entityUnloaded(first, helper.level)
            runtime.manager.entityUnloaded(second, helper.level)
            first.discard()
            second.discard()
        }
    }

    @GameTest(maxTicks = 800)
    fun passengerRelationshipsAreBlockedForPendingAndLinkedProps(helper: GameTestHelper) = checkConnectionPassengerPolicy(helper, ToolGunMode.WELD)

    @GameTest(maxTicks = 800)
    fun passengerRelationshipsAreBlockedForRope(helper: GameTestHelper) = checkConnectionPassengerPolicy(helper, ToolGunMode.ROPE)

    @GameTest(maxTicks = 800)
    fun passengerRelationshipsAreBlockedForAxis(helper: GameTestHelper) = checkConnectionPassengerPolicy(helper, ToolGunMode.AXIS)

    @GameTest(maxTicks = 800)
    fun passengerRelationshipsAreBlockedForNoCollide(helper: GameTestHelper) = checkConnectionPassengerPolicy(helper, ToolGunMode.NO_COLLIDE)

    private fun checkConnectionPassengerPolicy(helper: GameTestHelper, mode: ToolGunMode) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 4, 1))
        val first = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        val second = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn second prop")
        val marker = net.minecraft.world.entity.item.ItemEntity(helper.level, origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble(), ItemStack(net.minecraft.world.item.Items.STONE))
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        repeat(mode.ordinal) { runtime.manager.cycleToolGunMode(player, 1) }
        var phase = 0
        var failure = "none"
        helper.succeedWhen {
            Thread.sleep(5)
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    if (first.uuid !in ready || second.uuid !in ready) return@succeedWhen helper.assertTrue(false, "bodies not ready")
                    helper.assertTrue(first.startRiding(marker, true, false), "could not create existing vehicle relationship")
                    helper.assertTrue(runtime.manager.useToolGun(player, first) == ToolGunResult.SELECTED, "selection failed")
                    val ridingResult = runtime.manager.useToolGun(player, second)
                    first.stopRiding()
                    if (ridingResult != ToolGunResult.FAILED) { failure = "linked a riding prop: $ridingResult"; phase = 99 }
                    helper.assertTrue(phase != 99, failure)
                    helper.assertTrue(marker.startRiding(first, true, false), "could not create existing passenger relationship")
                    helper.assertTrue(runtime.manager.useToolGun(player, first) == ToolGunResult.SELECTED, "second selection failed")
                    val carryingResult = runtime.manager.useToolGun(player, second)
                    marker.stopRiding()
                    if (carryingResult != ToolGunResult.FAILED) { failure = "linked a prop carrying a passenger: $carryingResult"; phase = 99 }
                    helper.assertTrue(phase != 99, failure)
                    helper.assertTrue(runtime.manager.useToolGun(player, first) == ToolGunResult.SELECTED, "final selection failed")
                    helper.assertTrue(runtime.manager.useToolGun(player, second) == ToolGunResult.APPLIED, "valid weld failed")
                    helper.assertTrue(!first.startRiding(marker, true, false), "pending linked prop became a forced passenger")
                    helper.assertTrue(!marker.startRiding(first, true, false), "pending linked prop accepted a forced passenger")
                    phase = 1
                }
                99 -> helper.assertTrue(false, failure)
                1 -> {
                    if (TumbleApi.status(helper.level, first.uuid) != TumbleApi.PropStatus.READY || TumbleApi.status(helper.level, second.uuid) != TumbleApi.PropStatus.READY)
                        return@succeedWhen helper.assertTrue(false, "link not ready")
                    helper.assertTrue(!first.startRiding(marker, true, false), "linked prop became a forced passenger")
                    helper.assertTrue(!marker.startRiding(first, true, false), "linked prop accepted a forced passenger")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for passenger policy, phase=$phase")
        }
    }

    @GameTest(maxTicks = 800)
    fun cancelledPendingWeldIsNotResurrectedByLateAcknowledgement(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 4, 1))
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
        var separation = 0.0
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    if (first.uuid !in ready || second.uuid !in ready) return@succeedWhen helper.assertTrue(false, "bodies not ready")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, first) == ToolGunWeldResult.SELECTED, "selection failed")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, second) == ToolGunWeldResult.WELDED, "weld was not queued")
                    helper.assertTrue(runtime.manager.removeToolGunWelds(player, first), "pending weld could not be cancelled")
                    phase = 1
                }
                1 -> {
                    val bodies = runtime.manager.networkProps().associateBy { it.propId }
                    if (bodies[first.uuid] == null || bodies[second.uuid] == null)
                        return@succeedWhen helper.assertTrue(false, "bodies not ready after cancellation")
                    separation = second.x - first.x
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodies.getValue(second.uuid).bodyId, PhysicsVector(12.0, 0.0, 0.0)))
                    checkpoint = frame.physicsStep
                    phase = 2
                }
                2 -> if (frame.physicsStep - checkpoint >= 30) {
                    helper.assertTrue(second.x - first.x > separation + 0.5, "late acknowledgement recreated a cancelled weld")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for cancelled weld, phase=$phase")
        }
    }

    @GameTest(maxTicks = 1600)
    fun movedComponentUsesItsUnloadCheckpointAfterReopen(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = BlockPos(5904, 50, 0)
        for (x in 0..2) {
            helper.level.setChunkForced((origin.x shr 4) + x, 0, true)
            helper.level.getChunk((origin.x shr 4) + x, 0)
        }
        for (x in 0..25) for (z in 0..2) helper.level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val structure = AssemblyStructure.of(AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone))), listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val root = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level).apply {
            setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
            initializeAssembly(structure)
        }
        helper.assertTrue(helper.level.addFreshEntity(root), "could not add assembly")
        runtime.manager.entityLoaded(root, helper.level)
        val peer = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn peer")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var step = 0L
        var movedX = 0.0
        var rootCopy: PhysicsPropEntity? = null
        var peerCopy: PhysicsPropEntity? = null
        var reopenedService: ServerPhysicsService? = null
        var reopenedManager: PhysicsPropManager? = null
        helper.succeedWhen {
            Thread.sleep(5)
            reopenedManager?.tick()
            val frame = runtime.service.latestFrame(dimension)
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    if (root.uuid !in ready || peer.uuid !in ready) return@succeedWhen helper.assertTrue(false, "initial bodies not ready")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, root) == ToolGunWeldResult.SELECTED, "selection failed")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, peer) == ToolGunWeldResult.WELDED, "weld failed")
                    phase = 1
                }
                1 -> {
                    if (TumbleApi.status(helper.level, root.uuid) != TumbleApi.PropStatus.READY || TumbleApi.status(helper.level, peer.uuid) != TumbleApi.PropStatus.READY)
                        return@succeedWhen helper.assertTrue(false, "weld not ready")
                    val body = runtime.manager.networkProps().first { it.propId == peer.uuid }.bodyId
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, body, PhysicsVector(20.0, 0.0, 0.0)))
                    step = frame.physicsStep
                    phase = 2
                }
                2 -> if (frame.physicsStep - step >= 40) {
                    val bodies = runtime.manager.networkProps().associateBy { it.propId }
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodies.getValue(root.uuid).bodyId, PhysicsVector.ZERO))
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodies.getValue(peer.uuid).bodyId, PhysicsVector.ZERO))
                    step = frame.physicsStep
                    phase = 5
                }
                5 -> if (frame.physicsStep - step >= 5) {
                    movedX = root.x
                    helper.assertTrue(movedX > origin.x + 2.5, "component did not move far enough before unload")
                    fun copy(prop: PhysicsPropEntity): PhysicsPropEntity {
                        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
                        prop.saveWithoutId(output)
                        return requireNotNull(TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)).also {
                            it.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), output.buildResult()))
                        }
                    }
                    rootCopy = copy(root).also { it.setPos(it.x - 32.0, it.y, it.z) }
                    peerCopy = copy(peer).also { it.setPos(it.x + 32.0, it.y, it.z) }
                    runtime.manager.entityUnloaded(root, helper.level, true)
                    root.discard()
                    step = frame.physicsStep
                    phase = 3
                }
                3 -> if (frame.physicsStep - step >= 30) {
                    val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-unload-checkpoint")).also { it.start() }
                    reopenedService = service
                    service.submit(PhysicsCommand.CreateDimension(dimension))
                    val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service),
                        LoggerFactory.getLogger("tumble-unload-checkpoint"), TumbleTuning())
                    reopenedManager = manager
                    manager.entityLoaded(requireNotNull(rootCopy), helper.level)
                    manager.entityLoaded(requireNotNull(peerCopy), helper.level)
                    phase = 4
                }
                4 -> {
                    val body = requireNotNull(reopenedManager).networkProps().firstOrNull { it.propId == root.uuid }
                        ?: return@succeedWhen helper.assertTrue(false, "reopened root not ready")
                    val snapshot = requireNotNull(reopenedService).latestFrame(dimension).bodies[body.bodyId]
                        ?: return@succeedWhen helper.assertTrue(false, "reopened snapshot unavailable")
                    helper.assertTrue(snapshot.transform.position.x > origin.x + 2.5 &&
                        kotlin.math.abs(snapshot.transform.position.x - movedX) < 2.0,
                        "unload restored a stale or inconsistent pose: ${snapshot.transform.position.x} vs $movedX")
                    requireNotNull(reopenedService).close()
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for unload checkpoint, phase=$phase")
        }
    }

    @GameTest(maxTicks = 1600)
    fun reopenedManagerWaitsForBothJournaledEndpoints(helper: GameTestHelper) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = BlockPos(5504, 50, 0)
        helper.level.setChunkForced(origin.x shr 4, 0, true)
        helper.level.getChunkAt(origin)
        for (x in 0..4) for (z in 0..2) helper.level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val structure = AssemblyStructure.of(AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone))), listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level).apply {
            setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
            initializeAssembly(structure)
        }
        helper.assertTrue(helper.level.addFreshEntity(assembly), "could not add assembly")
        runtime.manager.entityLoaded(assembly, helper.level)
        val peer = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn peer")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        var reopenedService: ServerPhysicsService? = null
        var reopenedManager: PhysicsPropManager? = null
        var restoredAssembly: PhysicsPropEntity? = null
        var restoredPeer: PhysicsPropEntity? = null
        var beforeX = 0.0
        var checkpointX = 0.0
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            reopenedManager?.tick()
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    if (assembly.uuid !in ready || peer.uuid !in ready) return@succeedWhen helper.assertTrue(false, "original bodies not ready")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, assembly) == ToolGunWeldResult.SELECTED, "weld selection failed")
                    helper.assertTrue(runtime.manager.useToolGunWeld(player, peer) == ToolGunWeldResult.WELDED, "weld creation failed")
                    phase = 1
                }
                1 -> {
                    if (TumbleApi.status(helper.level, assembly.uuid) != TumbleApi.PropStatus.READY ||
                        TumbleApi.status(helper.level, peer.uuid) != TumbleApi.PropStatus.READY)
                        return@succeedWhen helper.assertTrue(false, "original weld not installed")
                    val peerBody = runtime.manager.networkProps().first { it.propId == peer.uuid }
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, peerBody.bodyId, PhysicsVector(20.0, 0.0, 0.0)))
                    checkpoint = frame.physicsStep
                    phase = 5
                }
                5 -> if (frame.physicsStep - checkpoint >= 20) {
                    val bodies = runtime.manager.networkProps().associateBy { it.propId }
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodies.getValue(assembly.uuid).bodyId, PhysicsVector.ZERO))
                    runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodies.getValue(peer.uuid).bodyId, PhysicsVector.ZERO))
                    checkpoint = frame.physicsStep
                    phase = 6
                }
                6 -> if (frame.physicsStep - checkpoint >= 5) {
                    helper.assertTrue(helper.level.server.saveEverything(false, true, false), "normal server save failed")
                    val rootBody = runtime.manager.networkProps().first { it.propId == assembly.uuid }
                    checkpointX = requireNotNull(runtime.service.latestFrame(dimension).bodies[rootBody.bodyId]).transform.position.x
                    helper.assertTrue(checkpointX > origin.x + 0.6, "component did not move before its checkpoint")
                    fun copy(prop: PhysicsPropEntity): PhysicsPropEntity {
                        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
                        prop.saveWithoutId(output)
                        return requireNotNull(TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)).also {
                            it.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), output.buildResult()))
                        }
                    }
                    restoredAssembly = copy(assembly).also { it.setPos(it.x - 32.0, it.y, it.z) }
                    restoredPeer = copy(peer).also { it.setPos(it.x + 32.0, it.y, it.z) }
                    val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-reopen-test")).also { it.start() }
                    reopenedService = service
                    service.submit(PhysicsCommand.CreateDimension(dimension))
                    val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service),
                        LoggerFactory.getLogger("tumble-reopen-test"), TumbleTuning())
                    reopenedManager = manager
                    manager.entityLoaded(requireNotNull(restoredPeer), helper.level)
                    checkpoint = service.latestFrame(dimension).physicsStep
                    phase = 2
                }
                2 -> {
                    val service = requireNotNull(reopenedService)
                    if (service.latestFrame(dimension).physicsStep - checkpoint < 20) return@succeedWhen helper.assertTrue(false, "waiting for missing endpoint")
                    helper.assertTrue(requireNotNull(reopenedManager).networkProps().none { it.propId == peer.uuid }, "peer escaped the reopened journal gate")
                    requireNotNull(reopenedManager).entityLoaded(requireNotNull(restoredAssembly), helper.level)
                    phase = 3
                }
                3 -> {
                    val manager = requireNotNull(reopenedManager)
                    val bodies = manager.networkProps().associateBy { it.propId }
                    val peerBody = bodies[peer.uuid] ?: return@succeedWhen helper.assertTrue(false, "reopened peer not ready")
                    val rootBody = bodies[assembly.uuid] ?: return@succeedWhen helper.assertTrue(false, "reopened assembly not ready")
                    val rootSnapshot = requireNotNull(reopenedService).latestFrame(dimension).bodies[rootBody.bodyId]
                        ?: return@succeedWhen helper.assertTrue(false, "reopened root snapshot unavailable")
                    helper.assertTrue(kotlin.math.abs(rootSnapshot.transform.position.x - checkpointX) < 1.0,
                        "reopened group did not use the recent coherent checkpoint: ${rootSnapshot.transform.position.x} vs $checkpointX")
                    beforeX = requireNotNull(restoredAssembly).x
                    requireNotNull(reopenedService).submit(PhysicsCommand.SetLinearVelocity(dimension, peerBody.bodyId, PhysicsVector(40.0, 0.0, 0.0)))
                    checkpoint = requireNotNull(reopenedService).latestFrame(dimension).physicsStep
                    phase = 4
                }
                4 -> if (requireNotNull(reopenedService).latestFrame(dimension).physicsStep - checkpoint >= 20) {
                    helper.assertTrue(requireNotNull(restoredAssembly).x > beforeX + 0.1, "reopened journal did not restore the native weld")
                    requireNotNull(reopenedService).close()
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for reopened journal, phase=$phase")
        }
    }

    @GameTest(maxTicks = 800)
    fun removingWeldWithPeerUnloadedDoesNotRestoreIt(helper: GameTestHelper) = checkRemovingUnloadedPeer(helper, ToolGunMode.WELD)

    @GameTest(maxTicks = 800)
    fun removingRopeWithPeerUnloadedDoesNotRestoreIt(helper: GameTestHelper) = checkRemovingUnloadedPeer(helper, ToolGunMode.ROPE)

    @GameTest(maxTicks = 800)
    fun removingAxisWithPeerUnloadedDoesNotRestoreIt(helper: GameTestHelper) = checkRemovingUnloadedPeer(helper, ToolGunMode.AXIS)

    @GameTest(maxTicks = 800)
    fun removingNoCollideWithPeerUnloadedDoesNotRestoreIt(helper: GameTestHelper) = checkRemovingUnloadedPeer(helper, ToolGunMode.NO_COLLIDE)

    private fun checkRemovingUnloadedPeer(helper: GameTestHelper, mode: ToolGunMode) {
        val runtime = PhysicsServices.runtime(helper.level.server)!!
        val origin = helper.absolutePos(BlockPos(1, 4, 1))
        val first = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn first prop")
        var peer = runtime.manager.spawn(helper.level, PhysicsVector(origin.x + 2.5, origin.y + 0.5, origin.z + 0.5))
            ?: return helper.fail("could not spawn peer")
        val player = helper.makeMockPlayer(GameType.CREATIVE).apply {
            setPos(origin.x + 1.5, origin.y.toDouble(), origin.z + 2.5)
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(TumbleItems.TOOL_GUN))
        }
        repeat(mode.ordinal) { runtime.manager.cycleToolGunMode(player, 1) }
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        var removalAccepted = false
        var failure = "none"
        var savedPeer: net.minecraft.nbt.CompoundTag? = null
        var initialDistance = 0.0
        helper.succeedWhen {
            Thread.sleep(5)
            val frame = runtime.service.latestFrame(dimension)
            when (phase) {
                0 -> {
                    val ready = runtime.manager.networkProps().mapTo(mutableSetOf()) { it.propId }
                    if (first.uuid !in ready || peer.uuid !in ready) return@succeedWhen helper.assertTrue(false, "bodies are not ready")
                    val selected = runtime.manager.useToolGun(player, first)
                    val connected = runtime.manager.useToolGun(player, peer)
                    if (selected != ToolGunResult.SELECTED || connected != ToolGunResult.APPLIED) {
                        failure = "connection failed: mode=$mode, selected=$selected, connected=$connected"
                        phase = 99
                    } else {
                        checkpoint = frame.physicsStep
                        phase = 1
                    }
                    helper.assertTrue(phase != 99, failure)
                }
                1 -> if (frame.physicsStep - checkpoint >= 10) {
                    val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
                    peer.saveWithoutId(saved)
                    savedPeer = saved.buildResult()
                    runtime.manager.entityUnloaded(peer, helper.level, true)
                    peer.discard()
                    removalAccepted = runtime.manager.useToolGunPrimary(player, first)
                    phase = 2
                }
                2 -> {
                    helper.assertTrue(removalAccepted, "could not remove $mode while its peer was unloaded")
                    val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
                        ?: error("could not reload peer")
                    restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), requireNotNull(savedPeer)))
                    helper.assertTrue(helper.level.addFreshEntity(restored), "could not add reloaded peer")
                    runtime.manager.entityLoaded(restored, helper.level)
                    peer = restored
                    phase = 3
                }
                3 -> {
                    val body = runtime.manager.networkProps().firstOrNull { it.propId == peer.uuid }
                        ?: return@succeedWhen helper.assertTrue(false, "peer is not ready")
                    val firstBody = runtime.manager.networkProps().firstOrNull { it.propId == first.uuid }
                        ?: return@succeedWhen helper.assertTrue(false, "first prop is not ready")
                    initialDistance = peer.x - first.x
                    if (mode == ToolGunMode.NO_COLLIDE) {
                        val firstSnapshot = TumbleApi.snapshot(helper.level, first.uuid) ?: return@succeedWhen helper.assertTrue(false, "first snapshot unavailable")
                        runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, firstBody.bodyId, PhysicsVector.ZERO))
                        runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, body.bodyId, PhysicsVector.ZERO))
                        runtime.service.submit(PhysicsCommand.SetTransform(dimension, body.bodyId, firstSnapshot.transform))
                    } else runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, body.bodyId, PhysicsVector(12.0, 0.0, 0.0)))
                    checkpoint = frame.physicsStep
                    phase = 4
                }
                99 -> helper.assertTrue(false, failure)
                4 -> if (frame.physicsStep - checkpoint >= 30) {
                    if (mode == ToolGunMode.NO_COLLIDE) {
                        helper.assertTrue(first.position().distanceTo(peer.position()) > 0.5, "removed no-collide still suppressed collisions")
                    } else helper.assertTrue(peer.x - first.x > initialDistance + 0.5,
                        "peer did not separate after $mode removal: first=${first.position()}, peer=${peer.position()}, initial=$initialDistance")
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "waiting for unloaded-peer removal, phase=$phase")
        }
    }

    private fun writeRecoveryJournal(
        directory: java.nio.file.Path, id: UUID, helper: GameTestHelper, origin: BlockPos,
        definition: AssemblyDefinition, fenced: Boolean, passengerSafe: Boolean, started: Boolean
    ) {
        val recordClass = Class.forName("cc.modlabs.tumble.physics.AssemblyRecoveryRecord")
        val record = recordClass.getConstructor(String::class.java, BlockPos::class.java, AssemblyDefinition::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .newInstance(helper.level.physicsDimensionId().value, origin, definition, fenced, passengerSafe)
        val payload = recordClass.getMethod("encode").invoke(record) as ByteArray
        val journalClass = Class.forName("cc.modlabs.tumble.physics.AssemblyConversionJournal")
        val journal = journalClass.getConstructor(java.nio.file.Path::class.java).newInstance(directory)
        journalClass.getMethod("prepare", UUID::class.java, ByteArray::class.java).invoke(journal, id, payload)
        if (started) journalClass.getMethod("markStarted", UUID::class.java).invoke(journal, id)
    }

    private fun obstructProtectedSource(manager: PhysicsPropManager, helper: GameTestHelper, id: UUID, position: BlockPos) {
        val permit = manager.javaClass.getDeclaredField("conversionWritePermit").apply { isAccessible = true }
        permit.set(manager, id to (helper.level.physicsDimensionId().value to position))
        helper.assertTrue(helper.level.setBlock(position, Blocks.DIRT.defaultBlockState(), 3), "could not inject source obstruction")
    }

    @GameTest(maxTicks = 1200)
    fun linkedPropReloadWaitsForMissingAssemblyTerrain(helper: GameTestHelper) = checkLinkedReload(helper, 0)

    @GameTest(maxTicks = 1200)
    fun immediatelySavedLinkedPropWaitsForMissingAssemblyTerrain(helper: GameTestHelper) = checkLinkedReload(helper, 1)

    @GameTest(maxTicks = 1200)
    fun linkedPropSavedBeforeVisibilityChangesWaitsForAssembly(helper: GameTestHelper) = checkLinkedReload(helper, 2)

    @GameTest(maxTicks = 1600)
    fun unloadBeforeInstallAcknowledgementRetainsConnection(helper: GameTestHelper) = checkLinkedReload(helper, 3)

    @GameTest(maxTicks = 1600)
    fun ropeConnectionReturnsAfterReload(helper: GameTestHelper) = checkLinkedReload(helper, 2, ToolGunMode.ROPE)

    @GameTest(maxTicks = 1600)
    fun axisConnectionReturnsAfterReload(helper: GameTestHelper) = checkLinkedReload(helper, 2, ToolGunMode.AXIS)

    @GameTest(maxTicks = 1600)
    fun noCollideConnectionReturnsAfterReload(helper: GameTestHelper) = checkLinkedReload(helper, 2, ToolGunMode.NO_COLLIDE)

    private fun checkLinkedReload(helper: GameTestHelper, ordering: Int, mode: ToolGunMode = ToolGunMode.WELD) {
        val origin = BlockPos(3104 + ordering * 128 + mode.ordinal * 512, 50, 0)
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
        var assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
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
        repeat(mode.ordinal) { runtime.manager.cycleToolGunMode(player, 1) }
        val dimension = helper.level.physicsDimensionId()
        var phase = 0
        var checkpoint = 0L
        var weldFailure = "none"
        var undoWhilePaused: Boolean? = null
        var savedAssembly: net.minecraft.nbt.CompoundTag? = null
        var assemblyHeight = 0.0
        fun reloadOther(step: Long, otherFirst: Boolean = false) {
            val assemblyOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
            assembly.saveWithoutId(assemblyOutput)
            savedAssembly = assemblyOutput.buildResult()
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
                    val selected = runtime.manager.useToolGun(player, assembly)
                    val welded = runtime.manager.useToolGun(player, other)
                    weldFailure = "selection=$selected, connection=$welded, mode=$mode, root=${TumbleApi.status(helper.level, assembly.uuid)}, other=${TumbleApi.status(helper.level, other.uuid)}"
                    if (selected != ToolGunResult.SELECTED || welded != ToolGunResult.APPLIED) phase = 5
                    helper.assertTrue(phase != 5, weldFailure)
                    if (ordering == 3) {
                        val queueField = runtime.service.javaClass.getDeclaredField("acknowledgements").apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST")
                        val queue = queueField.get(runtime.service) as ConcurrentLinkedQueue<PhysicsAcknowledgement>
                        val deadline = System.nanoTime() + 5_000_000_000L
                        while (queue.none { it is PhysicsAcknowledgement.ConnectionPrepared } && System.nanoTime() < deadline) Thread.sleep(1)
                        val prepared = queue.filterIsInstance<PhysicsAcknowledgement.ConnectionPrepared>().singleOrNull()
                            ?: return@succeedWhen helper.assertTrue(false, "connection preparation not acknowledged")
                        val lockField = runtime.service.javaClass.getDeclaredField("stepBoundary").apply { isAccessible = true }
                        val lock = lockField.get(runtime.service) as java.util.concurrent.locks.ReentrantLock
                        lock.lock()
                        try {
                            val lockDeadline = System.nanoTime() + 5_000_000_000L
                            while (!lock.hasQueuedThreads() && System.nanoTime() < lockDeadline) Thread.sleep(1)
                            helper.assertTrue(lock.hasQueuedThreads(), "physics worker did not reach the step boundary")
                            runtime.manager.tick()
                            val pendingField = runtime.manager.javaClass.getDeclaredField("pendingConnections").apply { isAccessible = true }
                            val pending = (pendingField.get(runtime.manager) as Map<*, *>)[prepared.requestId]
                                ?: return@succeedWhen helper.assertTrue(false, "installation was not pending")
                            val record = pending.javaClass.getDeclaredField("record").apply { isAccessible = true }.get(pending)
                            helper.assertTrue(record != null, "connection was not durable before unload")
                            reloadOther(frame.physicsStep, otherFirst = true)
                            queue.add(PhysicsAcknowledgement.ConnectionCreated(prepared.requestId, false))
                        } finally {
                            lock.unlock()
                        }
                    } else {
                        checkpoint = frame.physicsStep
                        phase = 1
                    }
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
                    val status = TumbleApi.status(helper.level, other.uuid)
                    val snapshot = TumbleApi.snapshot(helper.level, other.uuid)
                    helper.assertTrue(status == TumbleApi.PropStatus.PENDING ||
                        status == TumbleApi.PropStatus.PAUSED && (snapshot == null || snapshot.suspended),
                        "linked body was not parked or paused: $status")
                    reloadOther(frame.physicsStep)
                }
                3 -> if (frame.physicsStep - checkpoint >= 30) {
                    val status = TumbleApi.status(helper.level, other.uuid)
                    helper.assertTrue((status == TumbleApi.PropStatus.PENDING || status == TumbleApi.PropStatus.PAUSED) &&
                        TumbleApi.snapshot(helper.level, other.uuid) == null,
                        "reloaded prop started while assembly terrain was missing: $status")
                    PhysicsServices.chunkLoaded(helper.level, remote.x shr 4, 0)
                    checkpoint = frame.physicsStep
                    phase = 4
                }
                4 -> if (frame.physicsStep - checkpoint >= 30) {
                    val status = TumbleApi.status(helper.level, other.uuid)
                    helper.assertTrue(status == TumbleApi.PropStatus.PENDING || status == TumbleApi.PropStatus.PAUSED,
                        "peer resumed before the assembly entity returned: $status")
                    val restored = TumbleEntities.PHYSICS_PROP.create(helper.level, EntitySpawnReason.COMMAND)
                        ?: error("could not reload assembly")
                    restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), requireNotNull(savedAssembly)))
                    helper.assertTrue(helper.level.addFreshEntity(restored), "could not add reloaded assembly")
                    runtime.manager.entityLoaded(restored, helper.level)
                    assembly = restored
                    phase = 6
                }
                5 -> helper.assertTrue(false, weldFailure)
                6 -> {
                    if (TumbleApi.status(helper.level, assembly.uuid) != TumbleApi.PropStatus.READY ||
                        TumbleApi.status(helper.level, other.uuid) != TumbleApi.PropStatus.READY
                    ) return@succeedWhen helper.assertTrue(false, "restored group is not ready")
                    val peer = runtime.manager.networkProps().first { it.propId == other.uuid }
                    val root = runtime.manager.networkProps().first { it.propId == assembly.uuid }
                    assemblyHeight = assembly.y
                    if (mode == ToolGunMode.NO_COLLIDE) {
                        val rootSnapshot = TumbleApi.snapshot(helper.level, assembly.uuid) ?: return@succeedWhen helper.assertTrue(false, "root snapshot unavailable")
                        runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, root.bodyId, PhysicsVector.ZERO))
                        runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, peer.bodyId, PhysicsVector.ZERO))
                        runtime.service.submit(PhysicsCommand.SetTransform(dimension, peer.bodyId, rootSnapshot.transform))
                    } else runtime.service.submit(PhysicsCommand.SetLinearVelocity(dimension, peer.bodyId, PhysicsVector(0.0, 40.0, 0.0)))
                    checkpoint = frame.physicsStep
                    phase = 7
                }
                7 -> if (frame.physicsStep - checkpoint >= 20) {
                    if (mode == ToolGunMode.NO_COLLIDE) {
                        helper.assertTrue(assembly.position().distanceTo(other.position()) < 0.5,
                            "restored no-collide did not keep overlapping props together")
                    } else helper.assertTrue(assembly.y > assemblyHeight + 0.1,
                        "reloaded assembly did not move with linked peer: mode=$mode, y=${assembly.y}, start=$assemblyHeight, " +
                            "assembly=${TumbleApi.status(helper.level, assembly.uuid)}, peer=${TumbleApi.status(helper.level, other.uuid)}, " +
                            "assemblySnapshot=${TumbleApi.snapshot(helper.level, assembly.uuid)}, peerSnapshot=${TumbleApi.snapshot(helper.level, other.uuid)}")
                    return@succeedWhen
                }
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
    fun earlierChunkIoFailurePreventsNewAssemblyConversion(helper: GameTestHelper) {
        val origin = BlockPos(50432, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val logger = LoggerFactory.getLogger("assembly-earlier-io-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        val directory = Files.createTempDirectory("tumble-earlier-io-test")
        val journal = directory.resolve("journal")
        val failuresField = PhysicsServices.javaClass.getDeclaredField("chunkIoFailures").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val failures = failuresField.get(PhysicsServices) as MutableMap<Any, Any>
        val previous = failures.remove(helper.level.server)
        try {
            val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning(), journal)
            PhysicsServices.chunkIoFailed(helper.level.server)
            val method = manager.javaClass.declaredMethods.single { it.name.startsWith("convertAssembly") && it.parameterCount == 4 }
            helper.assertTrue(method.invoke(manager, helper.level, listOf(origin), player, false) == null,
                "conversion ignored an earlier chunk I/O failure")
            helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE), "I/O failure consumed a source block")
            helper.assertTrue(!Files.exists(journal), "refused conversion wrote a journal")
            failures.remove(helper.level.server)
            val during = origin.east()
            helper.level.setBlock(during, Blocks.STONE.defaultBlockState(), 3)
            net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { level, _, position, _, _ ->
                if (level === helper.level && position == during) PhysicsServices.chunkIoFailed(level.server)
                true
            }
            helper.assertTrue(method.invoke(manager, helper.level, listOf(during), player, false) == null,
                "conversion ignored a chunk I/O failure during validation")
            helper.assertTrue(helper.level.getBlockState(during).`is`(Blocks.STONE), "validation-time I/O failure consumed a block")
        } finally {
            if (previous == null) failures.remove(helper.level.server) else failures[helper.level.server] = previous
            service.close()
            if (Files.isDirectory(journal)) {
                Files.list(journal).use { files -> files.forEach { Files.delete(it) } }
                Files.delete(journal)
            }
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }

    @GameTest
    fun earlyChunkIoFailurePreventsJournalReplay(helper: GameTestHelper) {
        val server = helper.level.server
        val runtimesField = PhysicsServices.javaClass.getDeclaredField("runtimes").apply { isAccessible = true }
        val failuresField = PhysicsServices.javaClass.getDeclaredField("chunkIoFailures").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val runtimes = runtimesField.get(PhysicsServices) as MutableMap<Any, Any>
        @Suppress("UNCHECKED_CAST")
        val failures = failuresField.get(PhysicsServices) as MutableMap<Any, Any>
        val runtime = runtimes.remove(server) ?: return helper.fail("runtime missing")
        val previousFailures = failures.remove(server)
        val directory = Files.createTempDirectory("tumble-early-io-test")
        val id = UUID.randomUUID()
        val logger = LoggerFactory.getLogger("assembly-early-io-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        try {
            PhysicsServices.chunkIoFailed(server)
            runtimes[server] = runtime
            val origin = BlockPos(50240, 4, 0)
            val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState())))
            writeRecoveryJournal(directory, id, helper, origin, definition, true, true, false)
            val manager = PhysicsPropManager(server, service, TerrainSectionTracker(service), logger, TumbleTuning(), directory)
            val recover = manager.javaClass.declaredMethods.single { it.name.startsWith("recoverConversions") }
            val blocked = try { recover.invoke(manager); false } catch (error: java.lang.reflect.InvocationTargetException) {
                error.cause is IllegalStateException
            }
            helper.assertTrue(blocked, "recovery ignored a chunk I/O failure reported before runtime registration")
            helper.assertTrue(Files.exists(directory.resolve("$id.journal")), "failed recovery deleted the journal")
            PhysicsServices.stopped(server)
            helper.assertTrue(server !in failures, "server stop retained its chunk I/O failure entry")
        } finally {
            runtimes[server] = runtime
            if (previousFailures == null) failures.remove(server) else failures[server] = previousFailures
            service.close()
            Files.deleteIfExists(directory.resolve("$id.journal"))
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }

    @GameTest
    fun journalWriteFailureDoesNotRemoveBlocks(helper: GameTestHelper) {
        val origin = BlockPos(2400, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val logger = LoggerFactory.getLogger("assembly-journal-write-failure-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        val directory = Files.createTempDirectory("tumble-journal-test")
        val blocked = directory.resolve("journal")
        try {
            val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning(), blocked)
            Files.writeString(blocked, "obstruction")
            val method = manager.javaClass.declaredMethods.single { it.name.startsWith("convertAssembly") && it.parameterCount == 4 }
            helper.assertTrue(method.invoke(manager, helper.level, listOf(origin), player, false) == null,
                "conversion accepted a failed journal write")
            helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE), "journal write failure removed a block")
            helper.assertTrue(Files.readString(blocked) == "obstruction", "journal write overwrote another file")
        } finally {
            service.close()
            Files.deleteIfExists(blocked)
            Files.deleteIfExists(directory)
        }
        helper.succeed()
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
    fun interruptedRemovalRestoresOnlyBlocksActuallyRemoved(helper: GameTestHelper) {
        val origin = BlockPos(2336, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(origin.east(), Blocks.OAK_STAIRS.defaultBlockState(), 3)
        val manager = PhysicsServices.runtime(helper.level.server)!!.manager
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { level, _, position, _, _ ->
            if (level === helper.level && position == origin.east()) {
                val field = manager.javaClass.getDeclaredField("conversionLocks").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val locks = field.get(manager) as Map<Pair<String, BlockPos>, UUID>
                val id = locks.getValue(level.physicsDimensionId().value to position)
                obstructProtectedSource(manager, helper, id, position)
            }
            true
        }
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        helper.assertTrue(TumbleApi.convertAssembly(helper.level, listOf(origin, origin.east()), player) == null,
            "conversion accepted a block that changed during capture")
        helper.assertTrue(helper.level.getBlockState(origin).`is`(Blocks.STONE), "removed source block was not restored")
        helper.assertTrue(helper.level.getBlockState(origin.east()).`is`(Blocks.DIRT), "rollback overwrote the changed block")
        helper.succeed()
    }

    @GameTest
    fun legacyJournalWithMissingEntityIsRetained(helper: GameTestHelper) {
        val origin = BlockPos(50688, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3)
        val directory = Files.createTempDirectory("tumble-legacy-passenger-test")
        val id = UUID.randomUUID()
        val logger = LoggerFactory.getLogger("assembly-legacy-passenger-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        try {
            val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState())))
            writeRecoveryJournal(directory, id, helper, origin, definition, true, false, true)
            val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning(), directory)
            val recover = manager.javaClass.declaredMethods.single { it.name.startsWith("recoverConversions") }
            val blocked = try { recover.invoke(manager); false } catch (error: java.lang.reflect.InvocationTargetException) {
                error.cause is IllegalStateException
            }
            helper.assertTrue(blocked, "legacy journal assumed an unloaded passenger was absent")
            helper.assertTrue(helper.level.getBlockState(origin).isAir, "ambiguous recovery restored source blocks")
            helper.assertTrue(Files.exists(directory.resolve("$id.journal")), "ambiguous recovery deleted its journal")
        } finally {
            service.close()
            Files.deleteIfExists(directory.resolve("$id.journal"))
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }

    @GameTest
    fun legacyRecoveryRetainsAssemblyWithPassengers(helper: GameTestHelper) {
        val origin = BlockPos(50816, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3)
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)))
        ))
        val entity = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        entity.setPos(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
        entity.initializeAssembly(structure)
        entity.javaClass.getDeclaredField("recoveryOrigin").apply { isAccessible = true }.set(entity, origin)
        val passenger = net.minecraft.world.entity.item.ItemEntity(helper.level, origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble(), ItemStack(net.minecraft.world.item.Items.STONE))
        helper.assertTrue(passenger.startRiding(entity, true, false), "could not construct legacy passenger relationship")
        val logger = LoggerFactory.getLogger("assembly-legacy-passenger-relationship-test")
        val service = ServerPhysicsService(logger).also { it.start() }
        val directory = Files.createTempDirectory("tumble-legacy-passenger-relationship")
        val manager = PhysicsPropManager(helper.level.server, service, TerrainSectionTracker(service), logger, TumbleTuning(), directory)
        try {
            manager.entityLoaded(entity, helper.level)
            manager.tick()
            helper.assertTrue(!entity.isRemoved && helper.level.getBlockState(origin).isAir, "recovery discarded a passenger-bearing assembly")
            passenger.stopRiding()
            manager.tick()
            helper.assertTrue(entity.isRemoved && helper.level.getBlockState(origin) == stone, "recovery did not resume after dismount")
        } finally {
            service.close()
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }

    @GameTest
    fun pendingAssemblyCannotRideOrCarryAnotherEntity(helper: GameTestHelper) {
        val origin = BlockPos(50560, 4, 0)
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
        val other = net.minecraft.world.entity.item.ItemEntity(helper.level, origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble(), ItemStack(net.minecraft.world.item.Items.STONE))
        helper.assertTrue(!entity.startRiding(other, true, false), "pending assembly became a forced passenger")
        helper.assertTrue(!other.startRiding(entity, true, false), "pending assembly accepted a forced passenger")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.READY, "assembly did not become ready")
            helper.assertTrue(entity.startRiding(other, true, false), "completed assembly could not ride")
            entity.stopRiding()
            helper.assertTrue(other.startRiding(entity, true, false), "completed assembly could not carry a passenger")
            other.stopRiding()
        }
    }

    @GameTest
    fun mutableInputPositionsCannotChangeTheCapturedSource(helper: GameTestHelper) {
        val origin = BlockPos(50304, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        val mutable = BlockPos.MutableBlockPos(origin.x, origin.y, origin.z)
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { level, _, position, _, _ ->
            if (level === helper.level && position == origin) mutable.set(origin.x + 8, origin.y, origin.z)
            true
        }
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, listOf(mutable), player)
            ?: return helper.fail("mutable input changed the conversion")
        helper.assertTrue(helper.level.getBlockState(origin).isAir, "conversion removed the wrong source")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.READY, "assembly did not become ready")
            helper.assertTrue(helper.level.setBlock(origin, Blocks.DIRT.defaultBlockState(), 3), "mutable input left a stale source fence")
        }
    }

    @GameTest
    fun rejectedOrThrowingBreakCallbackReleasesSourceFence(helper: GameTestHelper) {
        val origin = BlockPos(50128, 4, 0)
        val throwing = origin.east(2)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        helper.level.setBlock(throwing, Blocks.STONE.defaultBlockState(), 3)
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { level, _, position, _, _ ->
            if (level === helper.level && position == throwing) throw IllegalStateException("injected callback failure")
            level !== helper.level || position != origin
        }
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        helper.assertTrue(TumbleApi.convertAssembly(helper.level, listOf(origin), player) == null, "denied conversion was accepted")
        helper.assertTrue(helper.level.setBlock(origin, Blocks.DIRT.defaultBlockState(), 3), "denial left the source locked")
        val threw = try {
            TumbleApi.convertAssembly(helper.level, listOf(throwing), player)
            false
        } catch (_: IllegalStateException) { true }
        helper.assertTrue(threw, "callback exception was not propagated")
        helper.assertTrue(helper.level.setBlock(throwing, Blocks.DIRT.defaultBlockState(), 3), "exception left the source locked")
        helper.succeed()
    }

    @GameTest
    fun pendingAssemblyProtectsItsSourceUntilCheckpoint(helper: GameTestHelper) {
        val origin = BlockPos(50000, 4, 0)
        helper.level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        helper.level.getChunkAt(origin)
        helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(helper.level, listOf(origin), player)
            ?: return helper.fail("could not start assembly conversion")
        helper.assertTrue(!helper.level.setBlock(origin, Blocks.DIRT.defaultBlockState(), 3), "pending source accepted a replacement")
        helper.assertTrue(!helper.level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3), "pending source accepted an identical replacement")
        helper.assertTrue(helper.level.getBlockState(origin).isAir, "pending source was changed")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.READY, "assembly did not become ready")
            helper.assertTrue(helper.level.setBlock(origin, Blocks.DIRT.defaultBlockState(), 3), "completed source stayed locked")
        }
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
        val journal = helper.level.server.getWorldPath(LevelResource.ROOT).resolve("tumble-assembly-journal").resolve("$id.journal")
        helper.assertTrue(Files.exists(journal), "pending assembly did not write its journal")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(TumbleApi.status(helper.level, id) == TumbleApi.PropStatus.READY, "assembly did not become ready")
            helper.assertTrue(!Files.exists(journal), "completed assembly kept its journal")
        }
    }

    @GameTest
    fun matchingBlockDoesNotProveRecoveryIsComplete(helper: GameTestHelper) {
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
            helper.assertTrue(helper.level.getBlockState(origin) == stone && helper.level.getBlockState(origin.east()).isAir,
                "recovery treated a matching block as proof of restoration")
            helper.assertTrue(!entity.isRemoved, "ambiguous recovery entity was discarded")
            helper.level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3)
            manager.tick()
            helper.assertTrue(helper.level.getBlockState(origin) == stone && helper.level.getBlockState(origin.east()) == stairs,
                "recovery did not restore the blocks after the obstruction was removed")
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
        obstructProtectedSource(runtime.manager, helper, id, origin.east())
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
        obstructProtectedSource(runtime.manager, helper, id, origin.east())
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
