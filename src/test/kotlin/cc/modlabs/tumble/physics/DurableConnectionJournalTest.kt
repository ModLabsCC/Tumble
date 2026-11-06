// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DurableConnectionJournalTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `journal preserves coherent component checkpoints across replacement`() {
        val path = directory.resolve("connections.dat")
        val first = UUID(0, 1)
        val second = UUID(0, 2)
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
        val checkpoint = listOf(
            DurableBodyState(first, PhysicsTransform(PhysicsVector(100.0, 10.0, 0.0)), PhysicsVector(3.0, 0.0, 0.0), PhysicsVector.ZERO),
            DurableBodyState(second, PhysicsTransform(PhysicsVector(102.0, 10.0, 0.0)), PhysicsVector(3.0, 0.0, 0.0), PhysicsVector.ZERO)
        )
        val record = DurableConnection(UUID.randomUUID(), "minecraft:overworld", second, first, UUID.randomUUID(), PhysicsJointKind.WELD, frames, 0f, false, checkpoint)
        DurableConnectionJournal(path).put(record)
        assertEquals(record, DurableConnectionJournal(path).records().single())
    }

    @Test
    fun `journal retains endpoint frame order and removes tombstoned records after reopening`() {
        val path = directory.resolve("connections.dat")
        val low = UUID(0, 1)
        val high = UUID(0, 2)
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 2.0, 3.0)), PhysicsTransform(PhysicsVector(4.0, 5.0, 6.0)))
        val forward = DurableConnection(UUID.randomUUID(), "minecraft:overworld", low, high, UUID.randomUUID(), PhysicsJointKind.WELD, frames, 0f, false)
        val reverse = DurableConnection(UUID.randomUUID(), "minecraft:overworld", high, low, UUID.randomUUID(), PhysicsJointKind.AXIS, frames, 0f, false)
        val journal = DurableConnectionJournal(path)
        journal.put(forward)
        journal.put(reverse)
        journal.put(forward.copy(deleted = true))
        assertEquals(listOf(reverse), DurableConnectionJournal(path).records())
    }

    @Test
    fun `abrupt writer termination leaves a readable journal`() {
        val path = directory.resolve("connections.dat")
        val classpath = listOf(JournalCrashWriter::class.java, DurableConnectionJournal::class.java,
            PhysicsJointFrames::class.java, kotlin.Unit::class.java)
            .map { java.nio.file.Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val executable = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(executable, "-cp", classpath, JournalCrashWriter::class.java.name, path.toString())
            .redirectErrorStream(true)
        builder.environment().remove("JAVA_TOOL_OPTIONS")
        val process = builder.start()
        try {
            assertEquals("READY", process.inputStream.bufferedReader().readLine())
            process.destroyForcibly()
            assertEquals(true, process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            val records = DurableConnectionJournal(path).records()
            assertEquals(true, records.size <= 1)
            records.singleOrNull()?.let { record ->
                assertEquals(UUID(0, 1), record.first)
                assertEquals(UUID(0, 2), record.second)
            }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `deleted records do not consume journal capacity`() {
        val path = directory.resolve("connections.dat")
        val journal = DurableConnectionJournal(path)
        repeat(40) {
            val record = DurableConnection(UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PhysicsJointKind.NO_COLLIDE, null, 0f, false)
            journal.put(record)
            journal.put(record.copy(deleted = true))
        }
        assertEquals(emptyList(), DurableConnectionJournal(path).records())
        assertEquals(true, Files.size(path) < 100)
    }

    @Test
    fun `failed journal replacement does not update in-memory records`() {
        val path = directory.resolve("connections.dat")
        val record = DurableConnection(UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PhysicsJointKind.NO_COLLIDE, null, 0f, false)
        val journal = DurableConnectionJournal(path)
        journal.put(record)
        Files.delete(path)
        Files.createDirectory(path)
        assertFailsWith<java.io.IOException> { journal.put(record.copy(deleted = true)) }
        assertEquals(listOf(record), journal.records())
    }

    @Test
    fun `corrupt journal fails closed instead of dropping connections`() {
        val path = directory.resolve("connections.dat")
        val journal = DurableConnectionJournal(path)
        journal.put(DurableConnection(UUID.randomUUID(), "minecraft:overworld", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), PhysicsJointKind.NO_COLLIDE, null, 0f, false))
        val bytes = Files.readAllBytes(path)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(path, bytes)
        assertFailsWith<java.io.IOException> { DurableConnectionJournal(path) }
    }
}

internal object JournalCrashWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val journal = DurableConnectionJournal(java.nio.file.Paths.get(args.single()))
        val record = DurableConnection(UUID(0, 3), "minecraft:overworld", UUID(0, 1), UUID(0, 2), UUID(0, 4), PhysicsJointKind.NO_COLLIDE, null, 0f, false)
        journal.put(record)
        println("READY")
        System.out.flush()
        while (true) {
            journal.put(record.copy(deleted = true))
            journal.put(record)
        }
    }
}
