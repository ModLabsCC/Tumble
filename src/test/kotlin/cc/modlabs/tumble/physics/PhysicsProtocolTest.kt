// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.physics.PhysicsBodyId
import cc.modlabs.tumble.physics.PhysicsBodySnapshot
import cc.modlabs.tumble.physics.PhysicsTransform
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhysicsProtocolTest {
    @Test
    fun `published snapshot frame copies and freezes its body map`() {
        val id = PhysicsBodyId(1)
        val source = mutableMapOf(
            id to PhysicsBodySnapshot(id, PhysicsTransform(PhysicsVector.ZERO), PhysicsVector.ZERO, PhysicsVector.ZERO, false)
        )
        val frame = PhysicsSnapshotFrame.of(7, source)

        source.clear()

        assertEquals(7, frame.physicsStep)
        assertEquals(setOf(id), frame.bodies.keys)
        assertFailsWith<UnsupportedOperationException> { (frame.bodies as MutableMap).clear() }
    }

    @Test
    fun `late body acknowledgement cannot attach after prop removal`() {
        val associations = PropBodyAssociations()
        val prop = UUID.randomUUID()
        associations.register(prop)
        assertTrue(associations.acknowledge(prop, PhysicsBodyId(1)))
        assertEquals(PhysicsBodyId(1), associations.remove(prop))
        assertFalse(associations.acknowledge(prop, PhysicsBodyId(2)))
    }

    @Test
    fun `body generations remain monotonic across runtime body recreation`() {
        val generations = mutableMapOf<UUID, Long>()
        val prop = UUID.randomUUID()

        assertEquals(1, generations.nextGeneration(prop))
        assertEquals(2, generations.nextGeneration(prop))
    }

    @Test
    fun `only newly spawned item entities qualify for drop conversion`() {
        assertTrue(isRecentPhysicalDrop(0))
        assertTrue(isRecentPhysicalDrop(2))
        assertFalse(isRecentPhysicalDrop(3))
    }
}
