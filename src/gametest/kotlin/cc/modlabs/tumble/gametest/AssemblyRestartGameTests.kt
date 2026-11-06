// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.entity.PhysicsPropEntity
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/** Runs in two separate server processes against the same on-disk world. */
class AssemblyRestartGameTests {
    private val origin = BlockPos(4096, 80, 0)
    private val idFile = Path.of("assembly-restart-id.txt")

    @GameTest(maxTicks = 1200)
    fun crashBaseline(helper: GameTestHelper) {
        if (System.getProperty("tumble.restart.phase") != "crashBaseline") return helper.succeed()
        val level = helper.level
        level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        level.getChunkAt(origin)
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        level.setBlock(origin.east(), Blocks.OAK_STAIRS.defaultBlockState(), 3)
        level.setBlock(origin.offset(3, 0, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3)
        helper.succeed()
    }

    @GameTest(maxTicks = 1200)
    fun crashInject(helper: GameTestHelper) {
        if (System.getProperty("tumble.restart.phase") != "crashInject") return helper.succeed()
        val level = helper.level
        level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        level.getChunkAt(origin)
        helper.assertTrue(level.getBlockState(origin).`is`(Blocks.STONE) &&
            level.getBlockState(origin.east()).`is`(Blocks.OAK_STAIRS), "baseline blocks not loaded")
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        ServerEntityEvents.ENTITY_LOAD.register { entity, entityLevel ->
            if (entityLevel === level && entity is PhysicsPropEntity && entity.blockPosition() == origin) {
                Files.writeString(Path.of("assembly-crash-reached.txt"),
                    "entity-load: ${level.getBlockState(origin)}, ${level.getBlockState(origin.east())}")
                Runtime.getRuntime().halt(86)
            }
        }
        TumbleApi.convertAssembly(level, listOf(origin, origin.east()), player)
        helper.fail("conversion did not reach the injected crash")
    }

    @GameTest(maxTicks = 1200)
    fun crashVerify(helper: GameTestHelper) {
        if (System.getProperty("tumble.restart.phase") != "crashVerify") return helper.succeed()
        val level = helper.level
        level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        level.getChunkAt(origin)
        helper.runAfterDelay(100) {
            val props = level.getEntitiesOfClass(PhysicsPropEntity::class.java, AABB(
                origin.x - 32.0, origin.y - 80.0, origin.z - 32.0,
                origin.x + 32.0, origin.y + 32.0, origin.z + 32.0
            ))
            helper.assertTrue(props.isEmpty(), "unexpected persisted assembly after injected crash")
            helper.assertTrue(level.getBlockState(origin) == Blocks.STONE.defaultBlockState() &&
                level.getBlockState(origin.east()) == Blocks.OAK_STAIRS.defaultBlockState(), "crash lost or changed source blocks")
            helper.assertTrue(level.getBlockState(origin.offset(3, 0, 0)).`is`(Blocks.DIAMOND_BLOCK), "baseline chunk was not persisted")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 1200)
    fun prepare(helper: GameTestHelper) {
        if (System.getProperty("tumble.restart.phase") != "prepare") return helper.succeed()
        val level = helper.level
        level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        level.getChunkAt(origin)
        for (x in -4..4) for (z in -4..4) level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3)
        level.setBlock(origin.offset(3, 0, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3)
        level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
        level.setBlock(origin.east(), Blocks.OAK_STAIRS.defaultBlockState(), 3)
        val player = helper.makeMockServerPlayer(GameType.CREATIVE) as ServerPlayer
        player.setPos(origin.x + 0.5, origin.y.toDouble(), origin.z + 2.0)
        val id = TumbleApi.convertAssembly(level, listOf(origin, origin.east()), player)
            ?: return helper.fail("conversion did not start")
        helper.succeedWhen {
            Thread.sleep(5)
            helper.assertTrue(TumbleApi.status(level, id) == TumbleApi.PropStatus.READY, "assembly was not ready")
            helper.assertTrue(level.getBlockState(origin).isAir && level.getBlockState(origin.east()).isAir, "source blocks remain")
            Files.writeString(idFile, id.toString())
        }
    }

    @GameTest(maxTicks = 1200)
    fun verify(helper: GameTestHelper) {
        if (System.getProperty("tumble.restart.phase") != "verify") return helper.succeed()
        val id = UUID.fromString(Files.readString(idFile).trim())
        val level = helper.level
        level.setChunkForced(origin.x shr 4, origin.z shr 4, true)
        level.getChunkAt(origin)
        helper.succeedWhen {
            Thread.sleep(5)
            val props = level.getEntitiesOfClass(PhysicsPropEntity::class.java, AABB(
                origin.x - 32.0, origin.y - 80.0, origin.z - 32.0,
                origin.x + 32.0, origin.y + 32.0, origin.z + 32.0
            ))
            helper.assertTrue(props.size == 1 && props.single().uuid == id, "expected the same single persisted assembly, found ${props.map { it.uuid }}")
            val blocks = props.single().assembly?.definition?.blocks
            helper.assertTrue(blocks?.size == 2 && blocks.associate { it.offset to it.state } == mapOf(
                BlockPos.ZERO to Blocks.STONE.defaultBlockState(),
                BlockPos(1, 0, 0) to Blocks.OAK_STAIRS.defaultBlockState()
            ), "persisted assembly lost or duplicated blocks")
            helper.assertTrue(level.getBlockState(origin.offset(3, 0, 0)).`is`(Blocks.DIAMOND_BLOCK), "source chunk did not persist")
            helper.assertTrue(level.getBlockState(origin).isAir && level.getBlockState(origin.east()).isAir, "source blocks reappeared")
            helper.assertTrue(TumbleApi.status(level, id) == TumbleApi.PropStatus.READY, "persisted physics body was not ready")
        }
    }
}
