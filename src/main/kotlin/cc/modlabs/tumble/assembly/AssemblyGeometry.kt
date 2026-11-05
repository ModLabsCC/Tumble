// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.assembly

import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsVector
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

object AssemblyGeometry {
    /** Captures collision on the server thread before the source blocks are removed. */
    fun capture(level: ServerLevel, origin: BlockPos, assembly: AssemblyDefinition): AssemblyStructure {
        check(level.server.isSameThread) { "Assembly geometry must be captured on the server thread" }
        val boxes = ArrayList<AssemblyCollisionBox>()
        assembly.blocks.forEachIndexed { index, block ->
            val position = origin.offset(block.offset)
            require(level.isInWorldBounds(position) && level.worldBorder.isWithinBounds(position) &&
                level.chunkSource.hasChunk(position.x shr 4, position.z shr 4) && level.getBlockState(position) == block.state
            ) { "Assembly block is unavailable or has changed" }
            val shapes = block.state.getCollisionShape(level, position).toAabbs()
            require(shapes.isNotEmpty() && boxes.size + shapes.size <= AssemblyStructure.MAX_BOXES) { "Unsupported assembly collision shape" }
            shapes.forEach { shape ->
                boxes += AssemblyCollisionBox(
                    index,
                    PhysicsBox(
                        PhysicsVector(block.offset.x + shape.minX - 0.5, block.offset.y + shape.minY - 0.5, block.offset.z + shape.minZ - 0.5),
                        PhysicsVector(block.offset.x + shape.maxX - 0.5, block.offset.y + shape.maxY - 0.5, block.offset.z + shape.maxZ - 0.5)
                    )
                )
            }
        }
        return AssemblyStructure.of(assembly, boxes)
    }
}
