// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AssemblyConversionJournalTest {
    @TempDir lateinit var directory: Path

    @Test
    fun recordsSurviveReopeningAndRejectCorruption() {
        val id = UUID.randomUUID()
        val bytes = "captured assembly".toByteArray()
        AssemblyConversionJournal(directory).prepare(id, bytes)
        val reopened = AssemblyConversionJournal(directory)
        assertContentEquals(bytes, reopened.pending().getValue(id).payload)
        assertFalse(reopened.pending().getValue(id).started)
        reopened.markStarted(id)
        assertTrue(AssemblyConversionJournal(directory).pending().getValue(id).started)
        val file = directory.resolve("$id.journal")
        Files.write(file, byteArrayOf(0))
        assertFails { reopened.pending() }
        reopened.complete(id)
        assertEquals(emptyMap(), AssemblyConversionJournal(directory).pending())
    }

    @Test
    fun refusesToPrepareWhenTheJournalDirectoryCannotBeCreated() {
        val obstruction = directory.resolve("not-a-directory")
        Files.writeString(obstruction, "keep")
        assertFails { AssemblyConversionJournal(obstruction.resolve("journal")).prepare(UUID.randomUUID(), byteArrayOf(1)) }
        assertEquals("keep", Files.readString(obstruction))
    }
}
