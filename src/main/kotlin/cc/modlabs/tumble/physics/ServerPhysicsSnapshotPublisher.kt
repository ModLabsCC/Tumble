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
    private var publicationSequence = 0L
    private var lastPublishedTick = -1L
    private var latestPhysicsStep = 0L
    private var statesSentLastTick = 0
    private var payloadsSentLastTick = 0
    private var sleepingOmittedLastTick = 0
    private var totalStatesSent = 0L
    private var totalPayloadsSent = 0L
    private val debugPlayers = mutableSetOf<UUID>()

    fun startTracking(entity: PhysicsPropEntity, player: ServerPlayer) {
        tracking.startTracking(entity.uuid, player.uuid)
        publish(player, setOf(entity.uuid), server.tickCount.toLong())
    }

    fun stopTracking(entity: PhysicsPropEntity, player: ServerPlayer) {
        if (tracking.stopTracking(entity.uuid, player.uuid) && ServerPlayNetworking.canSend(player, PhysicsDespawnPayload.TYPE)) {
            ServerPlayNetworking.send(player, PhysicsDespawnPayload(entity.uuid))
        }
    }

    fun propRemoved(propId: UUID) {
        tracking.removeProp(propId).forEach { playerId ->
            server.playerList.getPlayer(playerId)?.takeIf { ServerPlayNetworking.canSend(it, PhysicsDespawnPayload.TYPE) }
                ?.let { ServerPlayNetworking.send(it, PhysicsDespawnPayload(propId)) }
        }
    }

    fun playerDisconnected(playerId: UUID) {
        tracking.clearPlayer(playerId)
        debugPlayers.remove(playerId)
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
        debugPlayers.clear()
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
                    snapshot.linearVelocity,
                    snapshot.angularVelocity,
                    snapshot.sleeping,
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
