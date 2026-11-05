// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.assembly

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Collections
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

data class AssemblyBlock(val offset: BlockPos, val state: BlockState)

/** Durable block states and positions relative to an assembly's origin. */
class AssemblyDefinition private constructor(blocks: List<AssemblyBlock>) {
    val blocks: List<AssemblyBlock> = Collections.unmodifiableList(blocks.map { it.copy(offset = it.offset.immutable()) })
    val palette: List<BlockState> = Collections.unmodifiableList(this.blocks.map(AssemblyBlock::state).distinct())

    companion object {
        const val MAX_BLOCKS = 512
        private const val MAX_OFFSET = 256
        private const val MAX_SPAN = 64

        @JvmStatic
        fun of(blocks: List<AssemblyBlock>): AssemblyDefinition {
            require(blocks.size in 1..MAX_BLOCKS) { "Assembly must contain 1 to $MAX_BLOCKS blocks" }
            require(blocks.map(AssemblyBlock::offset).toSet().size == blocks.size) { "Assembly positions must be unique" }
            require(blocks.all { block ->
                block.offset.x in -MAX_OFFSET..MAX_OFFSET && block.offset.y in -MAX_OFFSET..MAX_OFFSET &&
                    block.offset.z in -MAX_OFFSET..MAX_OFFSET && !block.state.isAir && !block.state.hasBlockEntity()
            }) { "Assembly has an unsupported position or block" }
            require(blocks.maxOf { it.offset.x } - blocks.minOf { it.offset.x } <= MAX_SPAN &&
                blocks.maxOf { it.offset.y } - blocks.minOf { it.offset.y } <= MAX_SPAN &&
                blocks.maxOf { it.offset.z } - blocks.minOf { it.offset.z } <= MAX_SPAN
            ) { "Assembly spans too many blocks" }
            return AssemblyDefinition(blocks)
        }

        private val PACKED_BLOCK_CODEC: Codec<PackedBlock> = RecordCodecBuilder.create { builder ->
            builder.group(
                BlockPos.CODEC.fieldOf("offset").forGetter(PackedBlock::offset),
                Codec.INT.fieldOf("state").forGetter(PackedBlock::state)
            ).apply(builder, ::PackedBlock)
        }

        private val PACKED_CODEC: Codec<PackedAssembly> = RecordCodecBuilder.create { builder ->
            builder.group(
                BlockState.CODEC.listOf().fieldOf("palette").forGetter(PackedAssembly::palette),
                PACKED_BLOCK_CODEC.listOf().fieldOf("blocks").forGetter(PackedAssembly::blocks)
            ).apply(builder, ::PackedAssembly)
        }

        @JvmField
        val CODEC: Codec<AssemblyDefinition> = PACKED_CODEC.comapFlatMap(
            { packed ->
                try {
                    require(packed.palette.size in 1..MAX_BLOCKS) { "Invalid assembly palette size" }
                    require(packed.blocks.size in 1..MAX_BLOCKS) { "Invalid assembly block count" }
                    val definition = of(packed.blocks.map { block ->
                        AssemblyBlock(block.offset, requireNotNull(packed.palette.getOrNull(block.state)) {
                            "Invalid assembly palette index"
                        })
                    })
                    DataResult.success(definition)
                } catch (error: IllegalArgumentException) {
                    DataResult.error { error.message ?: "Invalid assembly" }
                }
            },
            { assembly ->
                val indices = assembly.palette.withIndex().associate { it.value to it.index }
                PackedAssembly(assembly.palette, assembly.blocks.map { PackedBlock(it.offset, indices.getValue(it.state)) })
            }
        )
    }
}

private data class PackedBlock(val offset: BlockPos, val state: Int)
private data class PackedAssembly(val palette: List<BlockState>, val blocks: List<PackedBlock>)
