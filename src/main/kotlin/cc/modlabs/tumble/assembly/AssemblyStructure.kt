// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.assembly

import cc.modlabs.tumble.physics.MinecraftTerrainAdapter
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsMaterialBox
import cc.modlabs.tumble.physics.PhysicsShapeDefinition
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.PhysicsAabb
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Collections

data class AssemblyCollisionBox(val block: Int, val box: PhysicsBox)

/** The palette and the exact collision captured before conversion. */
class AssemblyStructure private constructor(val definition: AssemblyDefinition, parts: List<AssemblyCollisionBox>) {
    val parts: List<AssemblyCollisionBox> = Collections.unmodifiableList(parts.toList())
    val shape = PhysicsShapeDefinition.MaterialBoxes(Collections.unmodifiableList(this.parts.map { part ->
        PhysicsMaterialBox(part.box, MinecraftTerrainAdapter.material(definition.blocks[part.block].state))
    }))
    val coverageBounds: List<PhysicsAabb> = Collections.unmodifiableList(this.parts.groupBy { part ->
        val offset = definition.blocks[part.block].offset
        Triple(Math.floorDiv(offset.x, 16), Math.floorDiv(offset.y, 16), Math.floorDiv(offset.z, 16))
    }.values.map { group ->
        PhysicsAabb(
            PhysicsVector(group.minOf { it.box.min.x }, group.minOf { it.box.min.y }, group.minOf { it.box.min.z }),
            PhysicsVector(group.maxOf { it.box.max.x }, group.maxOf { it.box.max.y }, group.maxOf { it.box.max.z })
        )
    })

    companion object {
        const val MAX_BOXES = 4096

        fun of(definition: AssemblyDefinition, parts: List<AssemblyCollisionBox>): AssemblyStructure {
            require(parts.size in 1..MAX_BOXES) { "Invalid assembly collision box count" }
            val covered = BooleanArray(definition.blocks.size)
            parts.forEach { part ->
                require(part.block in definition.blocks.indices) { "Invalid assembly collision owner" }
                val offset = definition.blocks[part.block].offset
                val min = part.box.min
                val max = part.box.max
                require(min.x >= offset.x - 2.5 && min.y >= offset.y - 2.5 && min.z >= offset.z - 2.5 &&
                    max.x <= offset.x + 2.5 && max.y <= offset.y + 2.5 && max.z <= offset.z + 2.5
                ) { "Assembly collision box is too far from its block" }
                covered[part.block] = true
            }
            require(covered.all { it }) { "Assembly contains a block without collision" }
            return AssemblyStructure(definition, parts)
        }

        private val VECTOR_CODEC: Codec<PhysicsVector> = Codec.DOUBLE.listOf().comapFlatMap(
            { values ->
                try {
                    require(values.size == 3) { "Expected three vector coordinates" }
                    DataResult.success(PhysicsVector(values[0], values[1], values[2]))
                } catch (error: IllegalArgumentException) {
                    DataResult.error { error.message ?: "Invalid vector" }
                }
            },
            { vector -> listOf(vector.x, vector.y, vector.z) }
        )

        private val PACKED_BOX_CODEC: Codec<PackedBox> = RecordCodecBuilder.create { builder ->
            builder.group(
                VECTOR_CODEC.fieldOf("min").forGetter(PackedBox::min),
                VECTOR_CODEC.fieldOf("max").forGetter(PackedBox::max)
            ).apply(builder, ::PackedBox)
        }

        private val BOX_CODEC: Codec<PhysicsBox> = PACKED_BOX_CODEC.comapFlatMap(
            { box ->
                try { DataResult.success(PhysicsBox(box.min, box.max)) }
                catch (error: IllegalArgumentException) { DataResult.error { error.message ?: "Invalid box" } }
            },
            { box -> PackedBox(box.min, box.max) }
        )

        private val PART_CODEC: Codec<AssemblyCollisionBox> = RecordCodecBuilder.create { builder ->
            builder.group(
                Codec.INT.fieldOf("block").forGetter(AssemblyCollisionBox::block),
                BOX_CODEC.fieldOf("box").forGetter(AssemblyCollisionBox::box)
            ).apply(builder, ::AssemblyCollisionBox)
        }

        private val PACKED_CODEC: Codec<PackedStructure> = RecordCodecBuilder.create { builder ->
            builder.group(
                AssemblyDefinition.CODEC.fieldOf("definition").forGetter(PackedStructure::definition),
                PART_CODEC.listOf().fieldOf("shapes").forGetter(PackedStructure::shapes)
            ).apply(builder, ::PackedStructure)
        }

        @JvmField
        val CODEC: Codec<AssemblyStructure> = PACKED_CODEC.comapFlatMap(
            { packed ->
                try { DataResult.success(of(packed.definition, packed.shapes)) }
                catch (error: IllegalArgumentException) { DataResult.error { error.message ?: "Invalid assembly" } }
            },
            { structure -> PackedStructure(structure.definition, structure.parts) }
        )
    }
}

private data class PackedBox(val min: PhysicsVector, val max: PhysicsVector)
private data class PackedStructure(val definition: AssemblyDefinition, val shapes: List<AssemblyCollisionBox>)
