// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import java.util.UUID

class PhysicsTrackingManager {
    private val tracking = mutableMapOf<UUID, MutableSet<UUID>>()
    private val forcedResets = mutableSetOf<TrackingKey>()
    private val delivered = mutableMapOf<TrackingKey, DeliveredState>()

    fun startTracking(propId: UUID, playerId: UUID) {
        tracking.getOrPut(propId, ::mutableSetOf) += playerId
        forcedResets += TrackingKey(propId, playerId)
    }

    fun stopTracking(propId: UUID, playerId: UUID): Boolean {
        var removed = false
        tracking[propId]?.also { players ->
            removed = players.remove(playerId)
            if (players.isEmpty()) tracking.remove(propId)
        }
        TrackingKey(propId, playerId).also {
            forcedResets -= it
            delivered.remove(it)
        }
        return removed
    }

    fun removeProp(propId: UUID): Set<UUID> {
        val players = tracking.remove(propId)?.toSet().orEmpty()
        players.forEach { playerId ->
            TrackingKey(propId, playerId).also {
                forcedResets -= it
                delivered.remove(it)
            }
        }
        return players
    }

    fun playersTracking(propId: UUID): Set<UUID> = tracking[propId]?.toSet().orEmpty()
    fun isTracking(propId: UUID, playerId: UUID) = playerId in tracking[propId].orEmpty()

    fun propsTrackedBy(playerId: UUID): Set<UUID> = tracking.asSequence()
        .filter { playerId in it.value }
        .mapTo(mutableSetOf()) { it.key }

    fun publicationFlags(propId: UUID, playerId: UUID, generation: Long, sleeping: Boolean): Int? {
        if (playerId !in tracking[propId].orEmpty()) return null
        val key = TrackingKey(propId, playerId)
        val previous = delivered[key]
        val reset = forcedResets.remove(key) || previous?.generation != generation
        if (!reset && sleeping && previous.sleeping) return null

        var flags = if (reset) PhysicsStateFlags.RESET else 0
        if (sleeping && previous?.sleeping == false) flags = flags or PhysicsStateFlags.SLEEP_TRANSITION
        if (!sleeping && previous?.sleeping == true) flags = flags or PhysicsStateFlags.WAKE_TRANSITION
        delivered[key] = DeliveredState(generation, sleeping)
        return flags
    }

    fun clearPlayer(playerId: UUID) {
        tracking.keys.toList().forEach { stopTracking(it, playerId) }
    }

    fun clear() {
        tracking.clear()
        forcedResets.clear()
        delivered.clear()
    }

    fun trackingPlayerCount() = tracking.values.flatten().toSet().size
    fun relationshipCount() = tracking.values.sumOf(Set<UUID>::size)

    private data class TrackingKey(val propId: UUID, val playerId: UUID)
    private data class DeliveredState(val generation: Long, val sleeping: Boolean)
}
