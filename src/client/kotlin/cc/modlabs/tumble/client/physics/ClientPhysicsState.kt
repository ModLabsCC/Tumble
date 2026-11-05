// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.network.PhysicsPropNetworkState
import cc.modlabs.tumble.network.PhysicsSnapshotPayload
import cc.modlabs.tumble.physics.PhysicsDimensionId
import java.util.LinkedHashMap
import java.util.UUID

object ClientPhysicsState {
    const val INTERPOLATION_DELAY_NANOS = 100_000_000L
    private const val ORPHAN_LIFETIME_NANOS = 1_000_000_000L
    private const val MAX_ORPHANS = 64

    private val trackedProps = mutableSetOf<UUID>()
    private val buffers = mutableMapOf<UUID, ClientPropInterpolationBuffer>()
    private val orphans = LinkedHashMap<UUID, PendingSample>()
    private var dimension: PhysicsDimensionId? = null
    private var rejectedStates = 0L

    fun changeDimension(newDimension: PhysicsDimensionId?) {
        if (dimension == newDimension) return
        clear()
        dimension = newDimension
    }

    fun track(propId: UUID, nowNanos: Long = System.nanoTime()) {
        trackedProps += propId
        orphans.remove(propId)?.takeIf { nowNanos - it.sample.arrivalNanos <= ORPHAN_LIFETIME_NANOS }
            ?.let { buffers.getOrPut(propId, ::ClientPropInterpolationBuffer).add(it.sample) }
    }

    fun untrack(propId: UUID) {
        trackedProps -= propId
        buffers.remove(propId)
        orphans.remove(propId)
    }

    fun receive(payload: PhysicsSnapshotPayload, nowNanos: Long = System.nanoTime()) {
        if (payload.dimension != dimension) {
            rejectedStates += payload.states.size
            return
        }
        expireOrphans(nowNanos)
        payload.states.forEach { state -> receiveState(payload, state, nowNanos) }
    }

    fun sample(propId: UUID, nowNanos: Long = System.nanoTime()): InterpolatedPropTransform? =
        buffers[propId]?.sample(nowNanos - INTERPOLATION_DELAY_NANOS)

    fun clear() {
        trackedProps.clear()
        buffers.clear()
        orphans.clear()
    }

    fun disconnect() {
        clear()
        dimension = null
        rejectedStates = 0
    }

    fun bufferSize(propId: UUID) = buffers[propId]?.size() ?: 0
    fun orphanCount() = orphans.size
    fun rejectedStateCount() = rejectedStates

    private fun receiveState(payload: PhysicsSnapshotPayload, state: PhysicsPropNetworkState, nowNanos: Long) {
        val sample = PropSample(
            payload.publicationSequence,
            payload.serverPhysicsStep,
            nowNanos,
            state.bodyGeneration,
            state.position,
            state.rotation,
            state.linearVelocity,
            state.angularVelocity,
            state.sleeping,
            state.flags
        )
        if (state.propId in trackedProps) {
            if (buffers.getOrPut(state.propId, ::ClientPropInterpolationBuffer).add(sample) == ClientPropInterpolationBuffer.AddResult.STALE) {
                rejectedStates++
            }
            return
        }
        val previous = orphans[state.propId]
        if (previous == null || sample.sequence > previous.sample.sequence || sample.generation != previous.sample.generation) {
            orphans[state.propId] = PendingSample(sample)
            while (orphans.size > MAX_ORPHANS) orphans.remove(orphans.keys.first())
        } else {
            rejectedStates++
        }
    }

    private fun expireOrphans(nowNanos: Long) {
        orphans.entries.removeIf { nowNanos - it.value.sample.arrivalNanos > ORPHAN_LIFETIME_NANOS }
    }

    private data class PendingSample(val sample: PropSample)
}
