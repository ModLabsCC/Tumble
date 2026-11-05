// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client

import cc.modlabs.tumble.client.network.ClientPhysicsNetworking
import cc.modlabs.tumble.entity.TumbleEntities
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.core.registries.BuiltInClientRegistries
import net.minecraft.client.renderer.entity.EntityRenderers
import org.slf4j.LoggerFactory

object TumbleClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("tumble")

    override fun onInitializeClient() {
        ClientPhysicsNetworking.register()
        ClientPhysicsGunControls.register()
        ClientToolGunControls.register()
        ClientToolGunState.registerHud()
        ClientPhysicsDebugHud.register()
        EntityRenderers.register(BuiltInClientRegistries.ENTITY_RENDERER, TumbleEntities.PHYSICS_PROP, ::PhysicsPropRenderer)
        logger.info("Project Tumble physics prop renderer registered")
    }
}
