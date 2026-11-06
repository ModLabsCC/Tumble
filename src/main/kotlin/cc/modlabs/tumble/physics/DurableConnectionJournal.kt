// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32

internal data class DurableConnection(
    val id: UUID,
    val dimension: String,
    val first: UUID,
    val second: UUID,
    val owner: UUID,
    val kind: PhysicsJointKind,
    val frames: PhysicsJointFrames?,
    val ropeLength: Float,
    val deleted: Boolean
) {
    init {
        require(dimension.isNotBlank() && dimension.length <= 512 && first != second)
        require(ropeLength.isFinite() && (kind != PhysicsJointKind.ROPE || ropeLength > 0f))
        require(kind != PhysicsJointKind.WELD && kind != PhysicsJointKind.AXIS || frames != null)
    }
}

internal class DurableConnectionJournal(private val path: Path) {
    private val entries = read()

    fun records(): List<DurableConnection> = entries.values.toList()

    fun put(record: DurableConnection) = putAll(listOf(record))

    fun putAll(records: Collection<DurableConnection>) {
        val next = LinkedHashMap(entries)
        records.forEach { next[it.id] = it }
        require(next.size <= MAX_RECORDS)
        persist(next.values)
        entries.clear()
        entries.putAll(next)
    }

    private fun read(): LinkedHashMap<UUID, DurableConnection> {
        val size = try { Files.size(path) } catch (_: java.nio.file.NoSuchFileException) { return linkedMapOf() }
        if (size > MAX_BYTES) throw IOException("Connection journal is too large")
        try {
            val input = DataInputStream(ByteArrayInputStream(Files.readAllBytes(path)))
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Invalid connection journal header")
            val size = input.readInt()
            if (size < 0 || size > MAX_BYTES - 20 || input.available() != size + 8) throw IOException("Invalid connection journal length")
            val payload = input.readNBytes(size)
            if (input.readLong() != CRC32().apply { update(payload) }.value) throw IOException("Connection journal checksum mismatch")
            val data = DataInputStream(ByteArrayInputStream(payload))
            val count = data.readInt()
            if (count !in 0..MAX_RECORDS) throw IOException("Invalid connection journal count")
            val result = linkedMapOf<UUID, DurableConnection>()
            repeat(count) {
                val id = data.readUuid()
                val dimension = data.readUTF()
                val first = data.readUuid()
                val second = data.readUuid()
                val owner = data.readUuid()
                val kind = PhysicsJointKind.entries.getOrNull(data.readUnsignedByte()) ?: throw IOException("Unknown connection kind")
                val frames = if (data.readBoolean()) PhysicsJointFrames(data.readTransform(), data.readTransform()) else null
                val record = DurableConnection(id, dimension, first, second, owner, kind, frames, data.readFloat(), data.readBoolean())
                if (result.put(id, record) != null) throw IOException("Duplicate connection journal ID")
            }
            if (data.available() != 0) throw IOException("Unexpected connection journal data")
            return result
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid connection journal record", error)
        }
    }

    private fun persist(records: Collection<DurableConnection>) {
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(records.size)
                records.forEach { record ->
                    output.writeUuid(record.id)
                    output.writeUTF(record.dimension)
                    output.writeUuid(record.first)
                    output.writeUuid(record.second)
                    output.writeUuid(record.owner)
                    output.writeByte(record.kind.ordinal)
                    output.writeBoolean(record.frames != null)
                    record.frames?.let {
                        output.writeTransform(it.first)
                        output.writeTransform(it.second)
                    }
                    output.writeFloat(record.ropeLength)
                    output.writeBoolean(record.deleted)
                }
            }
        }.toByteArray()
        if (payload.size > MAX_BYTES - 20) throw IOException("Connection journal is too large")
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(payload.size)
                output.write(payload)
                output.writeLong(CRC32().apply { update(payload) }.value)
            }
        }.toByteArray()
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".tumble-connections-", ".tmp")
        var moved = false
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // shortcut: directory metadata is not forced on Windows, upgrade before claiming power-loss durability.
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            moved = true
        } finally {
            if (!moved) Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val MAGIC = 0x54434a31
        const val VERSION = 1
        const val MAX_RECORDS = 50_000
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}

private fun DataOutputStream.writeUuid(value: UUID) {
    writeLong(value.mostSignificantBits)
    writeLong(value.leastSignificantBits)
}
private fun DataInputStream.readUuid() = UUID(readLong(), readLong())
private fun DataOutputStream.writeTransform(value: PhysicsTransform) {
    writeDouble(value.position.x)
    writeDouble(value.position.y)
    writeDouble(value.position.z)
    writeDouble(value.rotation.x)
    writeDouble(value.rotation.y)
    writeDouble(value.rotation.z)
    writeDouble(value.rotation.w)
}
private fun DataInputStream.readTransform() = PhysicsTransform(
    PhysicsVector(readDouble(), readDouble(), readDouble()),
    PhysicsRotation(readDouble(), readDouble(), readDouble(), readDouble())
)
