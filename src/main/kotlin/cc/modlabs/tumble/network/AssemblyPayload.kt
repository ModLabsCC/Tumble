// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import cc.modlabs.tumble.TumbleMod
import cc.modlabs.tumble.assembly.AssemblyBlock
import cc.modlabs.tumble.assembly.AssemblyCollisionBox
import cc.modlabs.tumble.assembly.AssemblyDefinition
import cc.modlabs.tumble.assembly.AssemblyStructure
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsDimensionId
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.level.block.Block

data class AssemblyPayload(val propId: UUID, val dimension: PhysicsDimensionId, val structure: AssemblyStructure) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AssemblyPayload>(TumbleMod.id("assembly"))
        val CODEC: StreamCodec<FriendlyByteBuf, AssemblyPayload> = object : StreamCodec<FriendlyByteBuf, AssemblyPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: AssemblyPayload) {
                buffer.writeUUID(payload.propId)
                buffer.writeUtf(payload.dimension.value, 128)
                val definition = payload.structure.definition
                buffer.writeVarInt(definition.palette.size)
                definition.palette.forEach { buffer.writeVarInt(Block.getId(it)) }
                val indices = definition.palette.withIndex().associate { it.value to it.index }
                buffer.writeVarInt(definition.blocks.size)
                definition.blocks.forEach { block ->
                    buffer.writeBlockPos(block.offset)
                    buffer.writeVarInt(indices.getValue(block.state))
                }
                buffer.writeVarInt(payload.structure.parts.size)
                payload.structure.parts.forEach { part ->
                    buffer.writeVarInt(part.block)
                    buffer.writeDouble(part.box.min.x).writeDouble(part.box.min.y).writeDouble(part.box.min.z)
                    buffer.writeDouble(part.box.max.x).writeDouble(part.box.max.y).writeDouble(part.box.max.z)
                }
            }

            override fun decode(buffer: FriendlyByteBuf): AssemblyPayload {
                val id = buffer.readUUID()
                val dimension = PhysicsDimensionId(buffer.readUtf(128))
                val paletteSize = buffer.readVarInt()
                require(paletteSize in 1..AssemblyDefinition.MAX_BLOCKS) { "Invalid assembly palette size" }
                val palette = List(paletteSize) {
                    requireNotNull(Block.BLOCK_STATE_REGISTRY.byId(buffer.readVarInt())) { "Unknown assembly block state" }
                }
                val blockCount = buffer.readVarInt()
                require(blockCount in 1..AssemblyDefinition.MAX_BLOCKS) { "Invalid assembly block count" }
                val blocks = List(blockCount) {
                    val position = buffer.readBlockPos()
                    val index = buffer.readVarInt()
                    AssemblyBlock(position, requireNotNull(palette.getOrNull(index)) { "Invalid assembly palette index" })
                }
                val definition = AssemblyDefinition.of(blocks)
                val shapeCount = buffer.readVarInt()
                require(shapeCount in 1..AssemblyStructure.MAX_BOXES) { "Invalid assembly collision count" }
                val parts = List(shapeCount) {
                    val owner = buffer.readVarInt()
                    val min = PhysicsVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
                    val max = PhysicsVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
                    AssemblyCollisionBox(owner, PhysicsBox(min, max))
                }
                return AssemblyPayload(id, dimension, AssemblyStructure.of(definition, parts))
            }
        }
    }
}

data class AssemblyRequestPayload(val propId: UUID) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AssemblyRequestPayload>(TumbleMod.id("assembly_request"))
        val CODEC: StreamCodec<FriendlyByteBuf, AssemblyRequestPayload> = object : StreamCodec<FriendlyByteBuf, AssemblyRequestPayload> {
            override fun encode(buffer: FriendlyByteBuf, payload: AssemblyRequestPayload) {
                buffer.writeUUID(payload.propId)
            }

            override fun decode(buffer: FriendlyByteBuf) = AssemblyRequestPayload(buffer.readUUID())
        }
    }
}
