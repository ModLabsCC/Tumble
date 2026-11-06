// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

internal data class PropPlacementRecord(
    val dimension: String,
    val position: BlockPos,
    val previous: BlockState,
    val placed: BlockState
) {
    fun encode(): ByteArray = JsonObject().apply {
        addProperty("dimension", dimension)
        add("position", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, position).result().orElseThrow())
        add("previous", BlockState.CODEC.encodeStart(JsonOps.INSTANCE, previous).result().orElseThrow())
        add("placed", BlockState.CODEC.encodeStart(JsonOps.INSTANCE, placed).result().orElseThrow())
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): PropPlacementRecord {
            val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val dimension = json.get("dimension").asString
            require(dimension.isNotBlank() && dimension.length <= 256)
            val previous = BlockState.CODEC.parse(JsonOps.INSTANCE, json.get("previous")).result().orElseThrow()
            val placed = BlockState.CODEC.parse(JsonOps.INSTANCE, json.get("placed")).result().orElseThrow()
            require(previous.isAir && !placed.isAir && !placed.hasBlockEntity())
            return PropPlacementRecord(dimension,
                BlockPos.CODEC.parse(JsonOps.INSTANCE, json.get("position")).result().orElseThrow(), previous, placed)
        }
    }
}
