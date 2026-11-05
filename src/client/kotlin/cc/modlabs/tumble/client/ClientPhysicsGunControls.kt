// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client

import cc.modlabs.tumble.client.network.ClientPhysicsNetworking
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.item.TumbleItems
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback
import net.minecraft.client.Minecraft

object ClientPhysicsGunControls {
    private var scroll = 0f
    private var yaw = 0f
    private var pitch = 0f

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
        ClientPreAttackCallback.EVENT.register { _, player, _ ->
            if (!player.isHolding { it.item === TumbleItems.GRAVITY_GUN }) {
                false
            } else {
                ClientPhysicsNetworking.launchGravityGun()
                true
            }
        }
    }

    fun onScroll(amount: Double): Boolean {
        if (Minecraft.getInstance().gui.screen() != null) return false
        if (heldProp() == null) return false
        scroll = (scroll + amount.toFloat()).coerceIn(-4f, 4f)
        return true
    }

    fun onMouseTurn(deltaX: Double, deltaY: Double): Boolean {
        if (!isRotationMode()) return false
        yaw = (yaw + deltaX.toFloat() * ROTATION_SENSITIVITY).coerceIn(-45f, 45f)
        pitch = (pitch + deltaY.toFloat() * ROTATION_SENSITIVITY).coerceIn(-45f, 45f)
        return true
    }

    fun isRotationMode(): Boolean {
        val client = Minecraft.getInstance()
        return client.gui.screen() == null && client.options.keyInventory.isDown && heldProp() != null
    }

    private fun tick(client: Minecraft) {
        val prop = heldProp() ?: return clear()
        if (scroll != 0f || yaw != 0f || pitch != 0f) {
            ClientPhysicsNetworking.controlPhysicsGun(prop.uuid, scroll, yaw, pitch)
            clear()
        }
    }

    private fun heldProp(): PhysicsPropEntity? {
        val client = Minecraft.getInstance()
        val player = client.player ?: return null
        val level = client.level ?: return null
        if (!player.isHolding { it.item === TumbleItems.PHYSICS_GUN }) return null
        return level.getEntitiesOfClass(PhysicsPropEntity::class.java, player.boundingBox.inflate(80.0)) {
            it.heldByEntityId == player.id
        }.firstOrNull()
    }

    private fun clear() {
        scroll = 0f
        yaw = 0f
        pitch = 0f
    }

    private const val ROTATION_SENSITIVITY = 0.15f
}
