// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import java.util.UUID

internal class AssemblyRequestBudget {
    private var windowStart = Int.MIN_VALUE
    private var bytesInWindow = 0
    private var currentTick = Int.MIN_VALUE
    private var requestsInTick = 0
    private val lastSent = mutableMapOf<UUID, Int>()

    fun allow(propId: UUID, tick: Int, bytes: Int): Boolean {
        if (bytes !in 0..MAX_BYTES_PER_SECOND) return false
        if (windowStart == Int.MIN_VALUE || tick < windowStart || tick - windowStart >= TICKS_PER_SECOND) {
            windowStart = tick
            bytesInWindow = 0
            lastSent.entries.removeIf { tick < it.value || tick - it.value >= TICKS_PER_SECOND }
        }
        if (currentTick != tick) {
            currentTick = tick
            requestsInTick = 0
        }
        val previous = lastSent[propId]
        if (previous != null && tick >= previous && tick - previous < TICKS_PER_SECOND) return false
        if (requestsInTick >= MAX_REQUESTS_PER_TICK || bytesInWindow + bytes > MAX_BYTES_PER_SECOND) return false
        requestsInTick++
        bytesInWindow += bytes
        lastSent[propId] = tick
        return true
    }

    private companion object {
        const val TICKS_PER_SECOND = 20
        const val MAX_REQUESTS_PER_TICK = 32
        const val MAX_BYTES_PER_SECOND = 512 * 1024
    }
}
