// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.network

import java.util.UUID

/** Tracks object identity so a late unload cannot remove a replacement sharing the UUID. */
class ClientEntityInstances<T : Any> {
    private val instances = mutableMapOf<UUID, T>()

    fun track(id: UUID, entity: T): Boolean {
        val previous = instances.put(id, entity)
        return previous != null && previous !== entity
    }

    fun untrack(id: UUID, entity: T): Boolean {
        if (instances[id] !== entity) return false
        instances.remove(id)
        return true
    }

    fun clear() = instances.clear()
}

