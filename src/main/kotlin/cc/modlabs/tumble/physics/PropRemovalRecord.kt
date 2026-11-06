// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.assembly.AssemblyDefinition
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

internal data class PropRemovalRecord(val dimension: String, val position: BlockPos, val state: BlockState, val definition: AssemblyDefinition?) {
    fun encode(): ByteArray = JsonObject().apply {
        addProperty("dimension", dimension)
        add("position", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, position).result().orElseThrow())
        add("state", BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).result().orElseThrow())
        definition?.let { add("definition", AssemblyDefinition.CODEC.encodeStart(JsonOps.INSTANCE, it).result().orElseThrow()) }
    }.toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(bytes: ByteArray): PropRemovalRecord {
            val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val dimension = json.get("dimension").asString
            require(dimension.isNotBlank() && dimension.length <= 256)
            return PropRemovalRecord(dimension,
                BlockPos.CODEC.parse(JsonOps.INSTANCE, json.get("position")).result().orElseThrow(),
                BlockState.CODEC.parse(JsonOps.INSTANCE, json.get("state")).result().orElseThrow(),
                json.get("definition")?.let { AssemblyDefinition.CODEC.parse(JsonOps.INSTANCE, it).result().orElseThrow() })
        }
    }
}
