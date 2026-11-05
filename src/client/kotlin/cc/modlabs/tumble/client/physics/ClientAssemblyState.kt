// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.network.AssemblyPayload
import cc.modlabs.tumble.network.AssemblyRequestPayload
import cc.modlabs.tumble.physics.PhysicsDimensionId
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import java.util.LinkedHashMap
import java.util.UUID

object ClientAssemblyState {
    private const val MAX_PENDING = 256
    private val tracked = mutableMapOf<UUID, PhysicsPropEntity>()
    private val assemblies = mutableMapOf<UUID, PhysicsPropEntity>()
    private val pending = LinkedHashMap<UUID, AssemblyPayload>()
    private var dimension: PhysicsDimensionId? = null

    fun changeDimension(value: PhysicsDimensionId?) {
        if (dimension == value) return
        tracked.clear()
        assemblies.clear()
        pending.clear()
        dimension = value
    }

    fun track(entity: PhysicsPropEntity) {
        tracked[entity.uuid] = entity
        pending.remove(entity.uuid)?.let { apply(entity, it) }
    }

    fun untrack(id: UUID) {
        tracked.remove(id)
        assemblies.remove(id)
        pending.remove(id)
    }

    fun receive(payload: AssemblyPayload) {
        if (payload.dimension != dimension) return
        val entity = tracked[payload.propId]
        if (entity != null) {
            apply(entity, payload)
            return
        }
        pending[payload.propId] = payload
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
    }

    fun assemblies(): Collection<PhysicsPropEntity> = assemblies.values

    fun requestMissing() {
        if (!ClientPlayNetworking.canSend(AssemblyRequestPayload.TYPE)) return
        tracked.values.asSequence().filter { it.hasAssembly && it.assembly == null }
            .take(MAX_REQUESTS_PER_TICK)
            .forEach { ClientPlayNetworking.send(AssemblyRequestPayload(it.uuid)) }
    }

    private fun apply(entity: PhysicsPropEntity, payload: AssemblyPayload) {
        entity.initializeAssembly(payload.structure)
        assemblies[entity.uuid] = entity
    }

    private const val MAX_REQUESTS_PER_TICK = 32
}
