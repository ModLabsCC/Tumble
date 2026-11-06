// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.assembly.AssemblyDefinition
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32
import net.minecraft.core.BlockPos

internal data class AssemblyRecoveryRecord(val dimension: String, val origin: BlockPos, val definition: AssemblyDefinition) {
    fun encode(): ByteArray = JsonObject().apply {
        addProperty("dimension", dimension)
        add("origin", BlockPos.CODEC.encodeStart(JsonOps.INSTANCE, origin).result().orElseThrow())
        add("definition", AssemblyDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition).result().orElseThrow())
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): AssemblyRecoveryRecord {
            val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            val dimension = json.get("dimension").asString
            require(dimension.isNotBlank() && dimension.length <= 256)
            return AssemblyRecoveryRecord(
                dimension,
                BlockPos.CODEC.parse(JsonOps.INSTANCE, json.get("origin")).result().orElseThrow(),
                AssemblyDefinition.CODEC.parse(JsonOps.INSTANCE, json.get("definition")).result().orElseThrow()
            )
        }
    }
}

/** Write-ahead records for server-process crashes; directory metadata is not power-loss safe. */
internal class AssemblyConversionJournal(private val directory: Path) {
    data class Pending(val payload: ByteArray, val started: Boolean)

    fun prepare(id: UUID, payload: ByteArray) {
        require(payload.size in 1..MAX_BYTES)
        Files.createDirectories(directory)
        val checksum = CRC32().apply { update(payload) }.value
        val data = ByteBuffer.allocate(16 + payload.size)
            .putInt(MAGIC).putInt(payload.size).putLong(checksum).put(payload).flip()
        FileChannel.open(path(id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            while (data.hasRemaining()) channel.write(data)
            channel.force(true)
        }
    }

    fun markStarted(id: UUID) {
        FileChannel.open(path(id), StandardOpenOption.APPEND, StandardOpenOption.WRITE).use { channel ->
            val marker = ByteBuffer.wrap(byteArrayOf(1))
            while (marker.hasRemaining()) channel.write(marker)
            channel.force(true)
        }
    }

    fun pending(): Map<UUID, Pending> {
        if (!Files.exists(directory)) return emptyMap()
        return Files.list(directory).use { files ->
            files.filter { it.fileName.toString().endsWith(SUFFIX) }.sorted().toList().associate { path ->
                val name = path.fileName.toString().removeSuffix(SUFFIX)
                val id = UUID.fromString(name)
                require(name == id.toString()) { "Invalid conversion journal filename: $path" }
                require(Files.size(path) in 17L..(MAX_BYTES + 17L)) { "Invalid conversion journal length: $path" }
                val bytes = Files.readAllBytes(path)
                require(bytes.size in 17..MAX_BYTES + 17) { "Invalid conversion journal length: $path" }
                val data = ByteBuffer.wrap(bytes)
                require(data.int == MAGIC) { "Invalid conversion journal header: $path" }
                val length = data.int
                require(length in 1..MAX_BYTES && bytes.size in (length + 16)..(length + 17)) { "Invalid conversion journal length: $path" }
                val checksum = data.long
                val payload = ByteArray(length).also(data::get)
                require(CRC32().apply { update(payload) }.value == checksum) { "Corrupt conversion journal: $path" }
                val started = data.hasRemaining()
                require(!started || data.get() == 1.toByte()) { "Invalid conversion journal marker: $path" }
                id to Pending(payload, started)
            }
        }
    }

    fun complete(id: UUID) {
        Files.delete(path(id))
    }

    private fun path(id: UUID) = directory.resolve("$id$SUFFIX")

    private companion object {
        const val MAGIC = 0x544a5231
        const val MAX_BYTES = 1 shl 20
        const val SUFFIX = ".journal"
    }
}
