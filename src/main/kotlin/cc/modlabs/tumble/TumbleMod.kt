// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble

import cc.modlabs.tumble.command.TumbleCommands
import cc.modlabs.tumble.entity.TumbleEntities
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.network.TumbleNetworking
import cc.modlabs.tumble.physics.PhysicsServices
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object TumbleMod : ModInitializer {
    private val logger = LoggerFactory.getLogger("tumble")

    override fun onInitialize() {
        TumbleGameRules.register()
        TumbleEntities.register()
        TumbleItems.register()
        TumbleNetworking.register()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> TumbleCommands.register(dispatcher) }
        ServerLifecycleEvents.SERVER_STARTING.register { PhysicsServices.start(it, logger) }
        ServerLifecycleEvents.SERVER_STOPPING.register(PhysicsServices::stop)
        ServerLevelEvents.LOAD.register(PhysicsServices::levelLoaded)
        ServerLevelEvents.UNLOAD.register(PhysicsServices::levelUnloaded)
        ServerEntityEvents.ENTITY_LOAD.register(PhysicsServices::entityLoaded)
        ServerEntityEvents.ENTITY_UNLOAD.register(PhysicsServices::entityUnloaded)
        EntityTrackingEvents.START_TRACKING.register(PhysicsServices::trackingStarted)
        EntityTrackingEvents.STOP_TRACKING.register(PhysicsServices::trackingStopped)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            PhysicsServices.playerDisconnected(handler.player)
        }
        ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ ->
            PhysicsServices.chunkLoaded(level, chunk.pos.x, chunk.pos.z)
        }
        ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk ->
            PhysicsServices.chunkUnloaded(level, chunk.pos.x, chunk.pos.z)
        }
        ServerTickEvents.END_SERVER_TICK.register(PhysicsServices::tick)
        logger.info("Project Tumble Fabric physics vertical slice initialized")
    }

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath("tumble", path)
}
