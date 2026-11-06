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
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks

class AssemblyTerrainGameTests {
    @GameTest
    fun axialRotationDoesNotRequireDistantChunks(helper: GameTestHelper) {
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of((0..64).map { AssemblyBlock(BlockPos(it, 0, 0), stone) })
        val structure = AssemblyStructure.of(definition, (0..64).map { x ->
            AssemblyCollisionBox(x, PhysicsBox(PhysicsVector(x - 0.5, -0.5, -0.5), PhysicsVector(x + 0.5, 0.5, 0.5)))
        })
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(0.5, 50.0, 8.5)
        assembly.initializeAssembly(structure)
        PhysicsPropEntity::class.java.getDeclaredField("angularVelocity").apply { isAccessible = true }
            .set(assembly, PhysicsVector(20.0, 0.0, 0.0))
        val tracker = TerrainSectionTracker(PhysicsServices.runtime(helper.level.server)!!.service)
        helper.assertTrue(ChunkPos.pack(5, 0) !in tracker.coveredChunks(assembly), "axial rotation inflated the rod length")
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
        helper.assertTrue(tipDestination !in tracker.coveredChunks(assembly), "stationary coverage already includes the tip destination")
        val velocity = PhysicsPropEntity::class.java.getDeclaredField("angularVelocity").apply { isAccessible = true }
        velocity.set(assembly, PhysicsVector(0.0, 2.0, 0.0))
        helper.assertTrue(tipDestination in tracker.coveredChunks(assembly), "rotating tip path is not covered")
        helper.succeed()
    }
}
