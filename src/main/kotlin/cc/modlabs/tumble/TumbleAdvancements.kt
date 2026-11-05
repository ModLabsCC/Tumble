// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

object TumbleAdvancements {
    fun award(player: Player, id: String) {
        val serverPlayer = player as? ServerPlayer ?: return
        val advancement = serverPlayer.level().server.advancements.get(TumbleMod.id(id)) ?: return
        serverPlayer.advancements.award(advancement, "triggered")
    }

    fun completed(player: Player, id: String): Boolean {
        val serverPlayer = player as? ServerPlayer ?: return false
        val advancement = serverPlayer.level().server.advancements.get(TumbleMod.id(id)) ?: return false
        return serverPlayer.advancements.getOrStartProgress(advancement).isDone
    }
}
