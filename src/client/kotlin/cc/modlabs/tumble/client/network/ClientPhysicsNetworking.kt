// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.network

import cc.modlabs.tumble.client.physics.ClientPhysicsState
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.network.PhysicsDespawnPayload
import cc.modlabs.tumble.network.PhysicsSnapshotPayload
import cc.modlabs.tumble.network.PhysicsPushPayload
import cc.modlabs.tumble.network.PhysicsGunControlPayload
import cc.modlabs.tumble.network.ToolGunStatePayload
import cc.modlabs.tumble.network.ToolGunModePayload
import cc.modlabs.tumble.network.GravityGunLaunchPayload
import cc.modlabs.tumble.network.PhysicsDebugPayload
import cc.modlabs.tumble.client.ClientPhysicsDebugHud
import cc.modlabs.tumble.client.ClientToolGunState
import cc.modlabs.tumble.physics.PhysicsDimensionId
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object ClientPhysicsNetworking {
    fun cycleToolGunMode(delta: Int) {
        if (ClientPlayNetworking.canSend(ToolGunModePayload.TYPE)) {
            ClientPlayNetworking.send(ToolGunModePayload(delta))
        }
    }

    fun launchGravityGun() {
        if (ClientPlayNetworking.canSend(GravityGunLaunchPayload.TYPE)) {
            ClientPlayNetworking.send(GravityGunLaunchPayload)
        }
    }

    fun controlPhysicsGun(propId: UUID, distanceDelta: Float, yawDelta: Float, pitchDelta: Float) {
        if (ClientPlayNetworking.canSend(PhysicsGunControlPayload.TYPE)) {
            ClientPlayNetworking.send(PhysicsGunControlPayload(propId, distanceDelta, yawDelta, pitchDelta))
        }
    }

    fun requestPush(propId: UUID, movement: PhysicsVector) {
        if (ClientPlayNetworking.canSend(PhysicsPushPayload.TYPE)) {
            ClientPlayNetworking.send(PhysicsPushPayload(propId, movement))
        }
    }

    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(PhysicsSnapshotPayload.TYPE) { payload, context ->
            context.client().execute { ClientPhysicsState.receive(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(PhysicsDespawnPayload.TYPE) { payload, context ->
            context.client().execute { ClientPhysicsState.untrack(payload.propId) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ToolGunStatePayload.TYPE) { payload, context ->
            context.client().execute { ClientToolGunState.receive(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(PhysicsDebugPayload.TYPE) { payload, context ->
            context.client().execute { ClientPhysicsDebugHud.receive(payload) }
        }
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity is PhysicsPropEntity) ClientPhysicsState.track(entity.uuid)
        }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            if (entity is PhysicsPropEntity) ClientPhysicsState.untrack(entity.uuid)
        }
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, level ->
            ClientPhysicsState.changeDimension(PhysicsDimensionId(level.dimension().identifier().toString()))
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.level?.let { ClientPhysicsState.changeDimension(PhysicsDimensionId(it.dimension().identifier().toString())) }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ClientPhysicsState.disconnect()
            ClientToolGunState.clear()
            ClientPhysicsDebugHud.clear()
        }
    }
}
