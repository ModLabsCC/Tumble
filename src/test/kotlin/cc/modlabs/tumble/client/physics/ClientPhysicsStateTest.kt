// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.network.PhysicsPropNetworkState
import cc.modlabs.tumble.network.PhysicsSnapshotPayload
import cc.modlabs.tumble.network.PhysicsStateFlags
import cc.modlabs.tumble.physics.PhysicsDimensionId
import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientPhysicsStateTest {
    @AfterTest
    fun cleanUp() = ClientPhysicsState.disconnect()

    @Test
    fun `buffer interpolates positions and shortest path quaternions`() {
        val buffer = ClientPropInterpolationBuffer()
        buffer.add(sample(1, 0, position = PhysicsVector.ZERO, rotation = PhysicsRotation.IDENTITY))
        buffer.add(sample(2, 100, position = PhysicsVector(6.0, 0.0, 0.0), rotation = PhysicsRotation(0.0, 0.0, 0.0, -1.0)))

        assertEquals(0.0, assertNotNull(buffer.sample(-1)).position.x)
        val middle = assertNotNull(buffer.sample(50))
        assertEquals(3.0, middle.position.x, 0.0001)
        assertTrue(abs(middle.rotation.w) > 0.999)
    }

    @Test
    fun `buffer extrapolates only within limit and holds sleeping transforms`() {
        val moving = ClientPropInterpolationBuffer()
        moving.add(sample(1, 1_000_000_000, velocity = PhysicsVector(10.0, 0.0, 0.0)))
        assertEquals(0.5, assertNotNull(moving.sample(1_050_000_000)).position.x, 0.0001)
        assertEquals(0.0, assertNotNull(moving.sample(1_100_000_001)).position.x, 0.0001)

        val sleeping = ClientPropInterpolationBuffer()
        sleeping.add(sample(1, 0, sleeping = true, velocity = PhysicsVector(10.0, 0.0, 0.0)))
        assertEquals(0.0, assertNotNull(sleeping.sample(50_000_000)).position.x)
    }

    @Test
    fun `generation reset large gap and correction clear bounded history`() {
        val buffer = ClientPropInterpolationBuffer()
        repeat(10) { index -> buffer.add(sample(index + 1L, index * 10L)) }
        assertEquals(ClientPropInterpolationBuffer.MAX_SAMPLES, buffer.size())
        assertEquals(ClientPropInterpolationBuffer.AddResult.RESET, buffer.add(sample(11, 700_000_000)))
        assertEquals(1, buffer.size())
        buffer.add(sample(12, 700_000_010, position = PhysicsVector(20.0, 0.0, 0.0)))
        assertEquals(1, buffer.size())
        buffer.add(sample(13, 700_000_020, generation = 2))
        assertEquals(2, buffer.generation())
        assertEquals(1, buffer.size())
    }

    @Test
    fun `explicit reset clears history and wake samples resume all axis rotation`() {
        val buffer = ClientPropInterpolationBuffer()
        val first = PhysicsRotation(0.2, 0.3, 0.4, 0.8).normalized()
        val second = PhysicsRotation(-0.5, 0.1, 0.6, 0.4).normalized()
        buffer.add(sample(1, 0, rotation = first, sleeping = true))
        buffer.add(sample(2, 100, rotation = second, flags = PhysicsStateFlags.WAKE_TRANSITION))
        val midpoint = assertNotNull(buffer.sample(50)).rotation
        assertEquals(1.0, midpoint.squaredLength(), 0.000001)
        assertTrue(abs(midpoint.x) > 0.01 && abs(midpoint.y) > 0.01 && abs(midpoint.z) > 0.01)

        buffer.add(sample(3, 110, rotation = second, flags = PhysicsStateFlags.RESET))
        assertEquals(1, buffer.size())
    }

    @Test
    fun `client rejects stale and wrong dimension packets and clears lifecycle state`() {
        val prop = UUID.randomUUID()
        val overworld = PhysicsDimensionId("minecraft:overworld")
        ClientPhysicsState.changeDimension(overworld)
        ClientPhysicsState.track(prop, 0)
        ClientPhysicsState.receive(payload(overworld, prop, 2, 1), 200_000_000)
        ClientPhysicsState.receive(payload(overworld, prop, 1, 1), 250_000_000)
        ClientPhysicsState.receive(payload(PhysicsDimensionId("minecraft:the_nether"), prop, 3, 1), 300_000_000)

        assertEquals(1, ClientPhysicsState.bufferSize(prop))
        assertTrue(ClientPhysicsState.rejectedStateCount() >= 2)
        ClientPhysicsState.changeDimension(PhysicsDimensionId("minecraft:the_nether"))
        assertNull(ClientPhysicsState.sample(prop, 400_000_000))
    }

    @Test
    fun `orphan packets are bounded promoted on tracking and removed on disconnect`() {
        val dimension = PhysicsDimensionId("minecraft:overworld")
        ClientPhysicsState.changeDimension(dimension)
        val promoted = UUID.randomUUID()
        ClientPhysicsState.receive(payload(dimension, promoted, 1, 1), 10)
        repeat(80) { ClientPhysicsState.receive(payload(dimension, UUID.randomUUID(), it + 2L, 1), 20 + it.toLong()) }
        assertTrue(ClientPhysicsState.orphanCount() <= 64)

        ClientPhysicsState.receive(payload(dimension, promoted, 100, 1), 200)
        ClientPhysicsState.track(promoted, 201)
        assertEquals(1, ClientPhysicsState.bufferSize(promoted))
        ClientPhysicsState.disconnect()
        assertEquals(0, ClientPhysicsState.orphanCount())
    }

    private fun sample(
        sequence: Long,
        arrival: Long,
        generation: Long = 1,
        position: PhysicsVector = PhysicsVector.ZERO,
        rotation: PhysicsRotation = PhysicsRotation.IDENTITY,
        velocity: PhysicsVector = PhysicsVector.ZERO,
        sleeping: Boolean = false,
        flags: Int = 0
    ) = PropSample(sequence, sequence, arrival, generation, position, rotation, velocity, PhysicsVector.ZERO, sleeping, flags)

    private fun payload(dimension: PhysicsDimensionId, prop: UUID, sequence: Long, generation: Long) =
        PhysicsSnapshotPayload(
            dimension,
            sequence,
            sequence,
            sequence,
            listOf(
                PhysicsPropNetworkState(
                    prop,
                    generation,
                    PhysicsVector.ZERO,
                    PhysicsRotation.IDENTITY,
                    PhysicsVector.ZERO,
                    PhysicsVector.ZERO,
                    false,
                    if (sequence == 1L) PhysicsStateFlags.RESET else 0
                )
            )
        )
}
