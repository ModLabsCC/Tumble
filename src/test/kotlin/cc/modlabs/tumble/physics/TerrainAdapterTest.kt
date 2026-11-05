// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.physics.PhysicsMaterial
import cc.modlabs.tumble.physics.PhysicsSectionPosition
import net.minecraft.core.BlockPos
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TerrainAdapterTest {
    @Test
    fun `section keys use floor division for negative coordinates`() {
        val dimension = PhysicsDimensionId("minecraft:overworld")
        assertEquals(PhysicsSectionPosition(-1, -1, -1), TerrainSectionKey.fromBlock(dimension, -1, -1, -1).position)
        assertEquals(PhysicsSectionPosition(-1, -1, -1), TerrainSectionKey.fromBlock(dimension, -16, -16, -16).position)
        assertEquals(PhysicsSectionPosition(-2, -2, -2), TerrainSectionKey.fromBlock(dimension, -17, -17, -17).position)
    }

    @Test
    fun `dirty section queue deduplicates and stays bounded`() {
        val queue = DirtySectionQueue()
        val dimension = PhysicsDimensionId("minecraft:overworld")
        val first = TerrainSectionKey(dimension, PhysicsSectionPosition(0, 0, 0))
        val second = TerrainSectionKey(dimension, PhysicsSectionPosition(1, 0, 0))
        queue.add(first)
        queue.add(first)
        queue.add(second)

        assertEquals(listOf(first), queue.take(1))
        assertEquals(listOf(second), queue.take(2))
    }

    @Test
    fun `urgent terrain sections bypass older background work`() {
        val queue = DirtySectionQueue()
        val dimension = PhysicsDimensionId("minecraft:overworld")
        val background = TerrainSectionKey(dimension, PhysicsSectionPosition(0, 10, 0))
        val floor = TerrainSectionKey(dimension, PhysicsSectionPosition(0, 3, 0))
        queue.add(background)
        queue.prioritize(floor)

        assertEquals(listOf(floor), queue.take(1))
        assertEquals(listOf(background), queue.take(1))
    }

    @Test
    fun `voxel boxes translate into world coordinates`() {
        val boxes = MinecraftTerrainAdapter.boxesAt(
            BlockPos(-2, 10, 3),
            listOf(AABB(0.0, 0.0, 0.25, 1.0, 0.5, 0.75)),
            PhysicsMaterial.LOW_FRICTION
        )

        assertEquals(-2.0, boxes.single().bounds.min.x)
        assertEquals(10.5, boxes.single().bounds.max.y)
        assertEquals(3.75, boxes.single().bounds.max.z)
        assertEquals(PhysicsMaterial.LOW_FRICTION, boxes.single().material)
    }

    @Test
    fun `dynamic voxel boxes are centered for the prop body`() {
        val shape = MinecraftTerrainAdapter.dynamicShape(listOf(
            AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
            AABB(0.0, 0.5, 0.0, 0.5, 1.0, 1.0)
        ))

        val compound = assertIs<PhysicsShapeDefinition.CompoundBoxes>(shape)
        assertEquals(-0.5, compound.boxes.first().min.x)
        assertEquals(0.0, compound.boxes.first().max.y)
        assertEquals(0.0, compound.boxes.last().max.x)
        assertNull(MinecraftTerrainAdapter.dynamicShape(List(65) { AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) }))
    }

    @Test
    fun `water overlap only counts the submerged height`() {
        assertEquals(0.25, verticalOverlap(10.0, 11.0, 9.0, 10.25))
        assertEquals(0.0, verticalOverlap(10.0, 11.0, 11.25, 12.0))
        assertEquals(1.0, verticalOverlap(10.0, 11.0, 9.0, 12.0))
        assertEquals(
            0.125,
            overlapVolume(AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), AABB(0.5, 0.5, 0.5, 1.5, 1.5, 1.5))
        )
    }

    @Test
    fun `sand receives drag but remains heavier than water`() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        val sand = MinecraftTerrainAdapter.physicsData(Blocks.SAND.defaultBlockState())

        assertEquals(1.6f, sand.material.density)
        assertEquals(true, sand.buoyancy.enabled)
        assertEquals(true, sand.buoyancy.forceScale < 1f)
    }

}
