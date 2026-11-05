// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import cc.modlabs.tumble.physics.PhysicsServices

object TumbleNetworking {
    fun register() {
        PayloadTypeRegistry.clientboundPlay().register(AssemblyPayload.TYPE, AssemblyPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(PhysicsSnapshotPayload.TYPE, PhysicsSnapshotPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(PhysicsDespawnPayload.TYPE, PhysicsDespawnPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ToolGunStatePayload.TYPE, ToolGunStatePayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(PhysicsDebugPayload.TYPE, PhysicsDebugPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(PhysicsPushPayload.TYPE, PhysicsPushPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(PhysicsGunControlPayload.TYPE, PhysicsGunControlPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(GravityGunLaunchPayload.TYPE, GravityGunLaunchPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ToolGunModePayload.TYPE, ToolGunModePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(AssemblyRequestPayload.TYPE, AssemblyRequestPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(AssemblyRequestPayload.TYPE) { payload, context ->
            context.server().execute { PhysicsServices.requestAssembly(context.player(), payload.propId) }
        }
        ServerPlayNetworking.registerGlobalReceiver(PhysicsPushPayload.TYPE) { payload, context ->
            context.server().execute {
                PhysicsServices.requestPush(context.player(), payload.propId, payload.movement)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(PhysicsGunControlPayload.TYPE) { payload, context ->
            context.server().execute {
                PhysicsServices.controlPhysicsGun(
                    context.player(), payload.propId, payload.distanceDelta, payload.yawDelta, payload.pitchDelta
                )
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(GravityGunLaunchPayload.TYPE) { _, context ->
            context.server().execute {
                PhysicsServices.launchGravityGun(context.player())
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(ToolGunModePayload.TYPE) { payload, context ->
            context.server().execute {
                PhysicsServices.cycleToolGunMode(context.player(), payload.delta)
            }
        }
    }
}
