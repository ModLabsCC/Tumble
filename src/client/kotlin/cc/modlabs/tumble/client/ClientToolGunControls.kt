// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client

import cc.modlabs.tumble.client.network.ClientPhysicsNetworking
import cc.modlabs.tumble.item.TumbleItems
import net.minecraft.client.Minecraft
import net.minecraft.client.KeyMapping
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import com.mojang.blaze3d.platform.InputConstants
import cc.modlabs.tumble.TumbleMod

object ClientToolGunControls {
    private val category = KeyMapping.Category.register(TumbleMod.id("tool_gun"))
    private val menu = KeyMapping(
        "key.tumble.tool_menu",
        InputConstants.Type.KEYBOARD,
        InputConstants.KEY_R,
        category
    )

    fun register() {
        KeyMappingHelper.registerKeyMapping(menu)
    }

    fun menuOpen() = menu.isDown

    fun onScroll(amount: Double): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        if (client.gui.screen() != null || !player.isHolding { it.item === TumbleItems.TOOL_GUN } || amount == 0.0) {
            return false
        }
        ClientPhysicsNetworking.cycleToolGunMode(if (amount > 0.0) -1 else 1)
        return true
    }
}
