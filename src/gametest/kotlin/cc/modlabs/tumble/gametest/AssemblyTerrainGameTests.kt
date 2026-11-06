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
    fun rotatingAssemblyIncludesTerrainInItsPath(helper: GameTestHelper) {
        val stone = Blocks.STONE.defaultBlockState()
        val definition = AssemblyDefinition.of(listOf(AssemblyBlock(BlockPos.ZERO, stone), AssemblyBlock(BlockPos(15, 0, 0), stone)))
        val structure = AssemblyStructure.of(definition, listOf(
            AssemblyCollisionBox(0, PhysicsBox(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))),
            AssemblyCollisionBox(1, PhysicsBox(PhysicsVector(14.5, -0.5, -0.5), PhysicsVector(15.5, 0.5, 0.5)))
        ))
        val assembly = PhysicsPropEntity(TumbleEntities.PHYSICS_PROP, helper.level)
        assembly.setPos(0.5, 50.0, 0.5)
        assembly.initializeAssembly(structure)
        val velocity = PhysicsPropEntity::class.java.getDeclaredField("angularVelocity").apply { isAccessible = true }
        velocity.set(assembly, PhysicsVector(0.0, 1.0, 0.0))
        val tracker = TerrainSectionTracker(PhysicsServices.runtime(helper.level.server)!!.service)
        helper.assertTrue(ChunkPos.pack(0, -1) in tracker.coveredChunks(assembly),
            "terrain coverage did not include the rotating assembly's path")
        helper.succeed()
    }
}
