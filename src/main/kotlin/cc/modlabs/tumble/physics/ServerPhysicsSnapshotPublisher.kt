// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.network.PhysicsDespawnPayload
import cc.modlabs.tumble.network.PhysicsPropNetworkState
import cc.modlabs.tumble.network.PhysicsSnapshotPayload
import cc.modlabs.tumble.network.PhysicsTrackingManager
import cc.modlabs.tumble.network.PhysicsDebugPayload
import java.util.UUID
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import cc.modlabs.tumble.network.AssemblyPayload
import cc.modlabs.tumble.network.AssemblyRequestBudget
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

data class PhysicsNetworkDebugState(
    val publicationRateHz: Int,
    val latestPhysicsStep: Long,
    val publicationSequence: Long,
    val trackingPlayers: Int,
    val trackingRelationships: Int,
    val statesSentLastTick: Int,
    val payloadsSentLastTick: Int,
    val sleepingStatesOmittedLastTick: Int,
    val totalStatesSent: Long,
    val totalPayloadsSent: Long
)

class ServerPhysicsSnapshotPublisher(
    private val server: MinecraftServer,
    private val service: ServerPhysicsService,
    private val manager: PhysicsPropManager
) {
    private val tracking = PhysicsTrackingManager()
    private data class TrackingKey(val propId: UUID, val playerId: UUID)
    private data class PendingTracking(val entity: PhysicsPropEntity, val player: ServerPlayer, var deferredDespawn: Boolean = false)
    private val trackingInstances = mutableMapOf<TrackingKey, PhysicsPropEntity>()
    private val pendingTracking = mutableMapOf<TrackingKey, PendingTracking>()
    private var publicationSequence = 0L
    private var lastPublishedTick = -1L
    private var latestPhysicsStep = 0L
    private var statesSentLastTick = 0
    private var payloadsSentLastTick = 0
    private var sleepingOmittedLastTick = 0
    private var totalStatesSent = 0L
    private var totalPayloadsSent = 0L
    private val debugPlayers = mutableSetOf<UUID>()
    private val assemblyRequests = mutableMapOf<UUID, AssemblyRequestBudget>()

    fun deferTracking(entity: PhysicsPropEntity, player: ServerPlayer) {
        val key = TrackingKey(entity.uuid, player.uuid)
        pendingTracking[key] = PendingTracking(entity, player, pendingTracking[key]?.deferredDespawn == true)
    }

    fun resolveTracking(entity: PhysicsPropEntity, accepted: Boolean) {
        val matching = pendingTracking.filterValues { it.entity === entity }.toList()
        matching.forEach { (key, pending) ->
            pendingTracking.remove(key)
            if (accepted && !entity.isRemoved && !pending.player.isRemoved && pending.player.level() === entity.level()) {
                startTracking(entity, pending.player)
            } else sendDeferredDespawn(key, pending)
        }
    }

    fun startTracking(entity: PhysicsPropEntity, player: ServerPlayer) {
        val key = TrackingKey(entity.uuid, player.uuid)
        if (pendingTracking[key]?.entity === entity) pendingTracking.remove(key)
        trackingInstances[key] = entity
        tracking.startTracking(entity.uuid, player.uuid)
        if (player.connection == null) return
        entity.assembly?.let { structure ->
            if (ServerPlayNetworking.canSend(player, AssemblyPayload.TYPE)) {
                ServerPlayNetworking.send(player, AssemblyPayload(entity.uuid, (entity.level() as net.minecraft.server.level.ServerLevel).physicsDimensionId(), structure))
            }
        }
        publish(player, setOf(entity.uuid), server.tickCount.toLong())
    }

    private fun sendDespawn(player: ServerPlayer, propId: UUID) {
        if (player.connection != null && ServerPlayNetworking.canSend(player, PhysicsDespawnPayload.TYPE)) {
            ServerPlayNetworking.send(player, PhysicsDespawnPayload(propId))
        }
    }

    private fun sendDeferredDespawn(key: TrackingKey, pending: PendingTracking) {
        if (pending.deferredDespawn && trackingInstances[key] == null && pendingTracking[key] == null) {
            sendDespawn(pending.player, key.propId)
        }
    }

    private fun stopActiveTracking(key: TrackingKey, entity: PhysicsPropEntity, player: ServerPlayer?) {
        if (trackingInstances[key] !== entity) return
        trackingInstances.remove(key)
        if (!tracking.stopTracking(key.propId, key.playerId)) return
        val pending = pendingTracking[key]
        if (pending != null && pending.entity !== entity) pending.deferredDespawn = true
        else if (player != null) sendDespawn(player, key.propId)
    }

    fun stopTracking(entity: PhysicsPropEntity, player: ServerPlayer) {
        val key = TrackingKey(entity.uuid, player.uuid)
        val pending = pendingTracking[key]
        if (pending?.entity === entity) {
            pendingTracking.remove(key)
            sendDeferredDespawn(key, pending)
        }
        stopActiveTracking(key, entity, player)
    }

    fun retireTracking(entity: PhysicsPropEntity) {
        val pendingForEntity = pendingTracking.filterValues { it.entity === entity }.toList()
        pendingForEntity.forEach { (key, pending) ->
            pendingTracking.remove(key)
            sendDeferredDespawn(key, pending)
        }
        trackingInstances.filterValues { it === entity }.keys.toList().forEach { key ->
            stopActiveTracking(key, entity, server.playerList.getPlayer(key.playerId))
        }
    }

    fun propRemoved(entity: PhysicsPropEntity) = retireTracking(entity)

    fun playerDisconnected(playerId: UUID) {
        tracking.clearPlayer(playerId)
        trackingInstances.keys.removeIf { it.playerId == playerId }
        pendingTracking.keys.removeIf { it.playerId == playerId }
        debugPlayers.remove(playerId)
        assemblyRequests.remove(playerId)
    }

    fun requestAssembly(player: ServerPlayer, propId: UUID) {
        if (!tracking.isTracking(propId, player.uuid) || !ServerPlayNetworking.canSend(player, AssemblyPayload.TYPE)) return
        val entity = manager.findProp(propId)?.takeIf { it.level() === player.level() } ?: return
        val structure = entity.assembly ?: return
        val estimatedBytes = 512 + structure.definition.palette.size * 5 + structure.definition.blocks.size * 13 + structure.parts.size * 53
        val budget = assemblyRequests.getOrPut(player.uuid, ::AssemblyRequestBudget)
        if (!budget.allow(propId, server.tickCount, estimatedBytes)) return
        ServerPlayNetworking.send(player, AssemblyPayload(propId, (entity.level() as net.minecraft.server.level.ServerLevel).physicsDimensionId(), structure))
    }

    fun toggleDebug(player: ServerPlayer): Boolean {
        val enabled = if (player.uuid in debugPlayers) {
            debugPlayers.remove(player.uuid)
            false
        } else {
            debugPlayers.add(player.uuid)
            true
        }
        if (enabled) publishDebug(player)
        return enabled
    }

    fun enableDebug(player: ServerPlayer) {
        debugPlayers.add(player.uuid)
        publishDebug(player)
    }

    fun tick() {
        val gameTick = server.tickCount.toLong()
        if (gameTick == lastPublishedTick) return
        lastPublishedTick = gameTick
        statesSentLastTick = 0
        payloadsSentLastTick = 0
        sleepingOmittedLastTick = 0
        server.playerList.players.forEach { player -> publish(player, tracking.propsTrackedBy(player.uuid), gameTick) }
        if (gameTick % DEBUG_INTERVAL_TICKS == 0L) {
            debugPlayers.mapNotNull(server.playerList::getPlayer).forEach(::publishDebug)
        }
    }

    fun close() {
        tracking.clear()
        trackingInstances.clear()
        pendingTracking.clear()
        debugPlayers.clear()
        assemblyRequests.clear()
    }

    fun debugState() = PhysicsNetworkDebugState(
        PUBLICATION_RATE_HZ,
        latestPhysicsStep,
        publicationSequence,
        tracking.trackingPlayerCount(),
        tracking.relationshipCount(),
        statesSentLastTick,
        payloadsSentLastTick,
        sleepingOmittedLastTick,
        totalStatesSent,
        totalPayloadsSent
    )

    private fun publish(player: ServerPlayer, propIds: Set<UUID>, gameTick: Long) {
        if (propIds.isEmpty() || !ServerPlayNetworking.canSend(player, PhysicsSnapshotPayload.TYPE)) return
        val grouped = manager.networkProps().asSequence()
            .filter { it.propId in propIds }
            .groupBy(NetworkProp::dimension)

        grouped.forEach { (dimension, props) ->
            val frame = service.latestFrame(dimension)
            if (frame.physicsStep == 0L) return@forEach
            val states = props.mapNotNull { prop ->
                val snapshot = frame.bodies[prop.bodyId] ?: return@mapNotNull null
                val flags = tracking.publicationFlags(
                    prop.propId,
                    player.uuid,
                    prop.bodyGeneration,
                    snapshot.sleeping
                ) ?: run {
                    sleepingOmittedLastTick++
                    return@mapNotNull null
                }
                PhysicsPropNetworkState(
                    prop.propId,
                    prop.bodyGeneration,
                    snapshot.transform.position,
                    snapshot.transform.rotation,
                    if (snapshot.suspended) PhysicsVector.ZERO else snapshot.linearVelocity,
                    if (snapshot.suspended) PhysicsVector.ZERO else snapshot.angularVelocity,
                    snapshot.sleeping || snapshot.suspended,
                    flags
                )
            }
            if (states.isEmpty()) return@forEach
            publicationSequence++
            latestPhysicsStep = maxOf(latestPhysicsStep, frame.physicsStep)
            states.chunked(PhysicsSnapshotPayload.MAX_STATES).forEach { batch ->
                ServerPlayNetworking.send(
                    player,
                    PhysicsSnapshotPayload(dimension, frame.physicsStep, gameTick, publicationSequence, batch)
                )
                statesSentLastTick += batch.size
                payloadsSentLastTick++
                totalStatesSent += batch.size
                totalPayloadsSent++
            }
        }
    }

    private fun publishDebug(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, PhysicsDebugPayload.TYPE)) return
        val physics = service.debugState()
        val network = debugState()
        ServerPlayNetworking.send(
            player,
            PhysicsDebugPayload(
                manager.registeredPropCount(),
                physics.runtimeBodies,
                physics.queuedCommands,
                physics.averageWorkMillis,
                network.statesSentLastTick,
                network.payloadsSentLastTick,
                network.sleepingStatesOmittedLastTick
            )
        )
    }

    companion object {
        const val PUBLICATION_RATE_HZ = 20
        const val DEBUG_INTERVAL_TICKS = 20L
    }
}
