// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import cc.modlabs.tumble.physics.PhysicsDimensionId
import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.ToolGunMode
import io.netty.buffer.Unpooled
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.core.Direction

class PhysicsSnapshotPayloadTest {
    @Test
    fun `single and multi state payloads round trip exactly`() {
        val first = state(UUID.randomUUID(), 3, PhysicsStateFlags.RESET)
        val second = state(UUID.randomUUID(), 4, PhysicsStateFlags.WAKE_TRANSITION)
        val payload = PhysicsSnapshotPayload(PhysicsDimensionId("minecraft:overworld"), 42, 17, 9, listOf(first, second))

        val decoded = roundTrip(payload)

        assertEquals(payload, decoded)
        assertEquals(first.propId, decoded.states.first().propId)
        assertEquals(first.rotation, decoded.states.first().rotation)
        assertEquals(9, decoded.publicationSequence)
        assertEquals(PhysicsStateFlags.WAKE_TRANSITION, decoded.states.last().flags)
    }

    @Test
    fun `codec and payload reject oversized invalid or non finite states`() {
        assertFailsWith<IllegalArgumentException> {
            PhysicsSnapshotPayload(
                PhysicsDimensionId("minecraft:overworld"),
                1,
                1,
                1,
                List(PhysicsSnapshotPayload.MAX_STATES + 1) { state(UUID.randomUUID(), 1, 0) }
            )
        }

        val invalidCount = FriendlyByteBuf(Unpooled.buffer())
        try {
            invalidCount.writeUtf("minecraft:overworld", 128).writeLong(1).writeLong(1).writeLong(1)
                .writeVarInt(PhysicsSnapshotPayload.MAX_STATES + 1)
            assertFailsWith<IllegalArgumentException> { PhysicsSnapshotPayload.CODEC.decode(invalidCount) }
        } finally {
            invalidCount.release()
        }

        val nonFinite = FriendlyByteBuf(Unpooled.buffer())
        try {
            nonFinite.writeUtf("minecraft:overworld", 128).writeLong(1).writeLong(1).writeLong(1).writeVarInt(1)
            nonFinite.writeUUID(UUID.randomUUID()).writeLong(1).writeDouble(Double.NaN)
            repeat(12) { nonFinite.writeDouble(0.0) }
            nonFinite.writeBoolean(false).writeInt(0)
            assertFailsWith<IllegalArgumentException> { PhysicsSnapshotPayload.CODEC.decode(nonFinite) }
        } finally {
            nonFinite.release()
        }
    }

    @Test
    fun `encoded payload size remains predictable for one and ten props`() {
        val id = UUID.randomUUID()
        assertEquals(178, encodedSize(listOf(state(id, 1, 0))))
        assertEquals(1375, encodedSize(List(10) { state(UUID.randomUUID(), 1, 0) }))
    }

    @Test
    fun `push payload round trips and rejects oversized movement`() {
        val expected = PhysicsPushPayload(UUID.randomUUID(), PhysicsVector(0.2, 0.0, -0.1))
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            PhysicsPushPayload.CODEC.encode(buffer, expected)
            assertEquals(expected, PhysicsPushPayload.CODEC.decode(buffer))
        } finally {
            buffer.release()
        }
        assertFailsWith<IllegalArgumentException> {
            PhysicsPushPayload(UUID.randomUUID(), PhysicsVector(1.1, 0.0, 0.0))
        }
    }

    @Test
    fun `physics gun controls round trip and reject unbounded input`() {
        val expected = PhysicsGunControlPayload(UUID.randomUUID(), 1f, -12.5f, 8f)
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            PhysicsGunControlPayload.CODEC.encode(buffer, expected)
            assertEquals(expected, PhysicsGunControlPayload.CODEC.decode(buffer))
        } finally {
            buffer.release()
        }
        assertFailsWith<IllegalArgumentException> {
            PhysicsGunControlPayload(UUID.randomUUID(), 0f, 46f, 0f)
        }
    }

    @Test
    fun `tool gun selection state round trips`() {
        val expected = ToolGunStatePayload(ToolGunMode.ROPE, UUID.randomUUID(), Direction.UP, true)
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ToolGunStatePayload.CODEC.encode(buffer, expected)
            assertEquals(expected, ToolGunStatePayload.CODEC.decode(buffer))
        } finally {
            buffer.release()
        }
        assertFailsWith<IllegalArgumentException> {
            ToolGunStatePayload(ToolGunMode.WELD, UUID.randomUUID(), null, false)
        }
    }

    @Test
    fun `physics debug payload round trips and rejects invalid metrics`() {
        val expected = PhysicsDebugPayload(4096, 4096, 3, 7.25, 128, 1, 3968)
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            PhysicsDebugPayload.CODEC.encode(buffer, expected)
            assertEquals(expected, PhysicsDebugPayload.CODEC.decode(buffer))
        } finally {
            buffer.release()
        }
        assertFailsWith<IllegalArgumentException> {
            PhysicsDebugPayload(-1, 0, 0, 0.0, 0, 0, 0)
        }
    }

    private fun roundTrip(payload: PhysicsSnapshotPayload): PhysicsSnapshotPayload {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        return try {
            PhysicsSnapshotPayload.CODEC.encode(buffer, payload)
            PhysicsSnapshotPayload.CODEC.decode(buffer)
        } finally {
            buffer.release()
        }
    }

    private fun encodedSize(states: List<PhysicsPropNetworkState>): Int {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        return try {
            PhysicsSnapshotPayload.CODEC.encode(
                buffer,
                PhysicsSnapshotPayload(PhysicsDimensionId("minecraft:overworld"), 1, 1, 1, states)
            )
            buffer.readableBytes()
        } finally {
            buffer.release()
        }
    }

    private fun state(id: UUID, generation: Long, flags: Int) = PhysicsPropNetworkState(
        id,
        generation,
        PhysicsVector(1.0, 2.0, 3.0),
        PhysicsRotation(0.1, 0.2, 0.3, 0.9).normalized(),
        PhysicsVector(4.0, 5.0, 6.0),
        PhysicsVector(0.4, 0.5, 0.6),
        false,
        flags
    )
}
