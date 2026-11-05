// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client

import cc.modlabs.tumble.TumbleMod
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.network.ToolGunStatePayload
import java.util.UUID
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import cc.modlabs.tumble.physics.ToolGunMode
import kotlin.math.cos
import kotlin.math.sin

object ClientToolGunState {
    var mode = ToolGunMode.WELD
        private set
    var selectedPropId: UUID? = null
        private set
    var selectedFace: Direction? = null
        private set
    private var canUndo = false

    fun registerHud() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.HELD_ITEM_TOOLTIP,
            TumbleMod.id("tool_gun_hud")
        ) { graphics, _ ->
            val client = Minecraft.getInstance()
            val player = client.player ?: return@attachElementAfter
            if (!player.isHolding { it.item === TumbleItems.TOOL_GUN } || client.gui.screen() != null) {
                return@attachElementAfter
            }
            val title = Component.translatable("hud.tumble.tool_gun.mode", Component.translatable("tool_mode.tumble.${mode.key}"))
            val detail = if (selectedPropId != null) {
                Component.translatable("hud.tumble.tool_gun.selected", selectedFace?.name ?: "?")
            } else {
                Component.translatable(if (mode == ToolGunMode.WELD && canUndo) {
                    "hud.tumble.tool_gun.ready_undo"
                } else {
                    "hud.tumble.tool_gun.help.${mode.key}"
                })
            }
            val width = maxOf(client.font.width(title), client.font.width(detail)) + 12
            val x = 8
            val y = graphics.guiHeight() / 2 + 24
            graphics.fill(x, y, x + width, y + 29, 0x99080C10.toInt())
            graphics.text(client.font, title, x + 6, y + 5, 0xFF55DDFF.toInt(), true)
            graphics.text(client.font, detail, x + 6, y + 17, 0xFFFFFFFF.toInt(), true)
            if (ClientToolGunControls.menuOpen()) {
                val centerX = graphics.guiWidth() / 2
                val centerY = graphics.guiHeight() / 2
                ToolGunMode.entries.forEachIndexed { index, entry ->
                    val angle = -Math.PI / 2.0 + index * Math.PI * 2.0 / ToolGunMode.entries.size
                    val label = Component.translatable("tool_mode.tumble.${entry.key}")
                    val labelWidth = client.font.width(label)
                    val labelX = centerX + (cos(angle) * 78).toInt() - labelWidth / 2
                    val labelY = centerY + (sin(angle) * 48).toInt() - 5
                    val selected = entry == mode
                    graphics.fill(labelX - 5, labelY - 3, labelX + labelWidth + 5, labelY + 12,
                        if (selected) 0xDD167A94.toInt() else 0xBB080C10.toInt())
                    graphics.text(client.font, label, labelX, labelY, if (selected) 0xFFFFFFFF.toInt() else 0xFFBBBBBB.toInt(), true)
                }
            }
        }
    }

    fun receive(payload: ToolGunStatePayload) {
        mode = payload.mode
        selectedPropId = payload.selectedPropId
        selectedFace = payload.selectedFace
        canUndo = payload.canUndo
    }

    fun clear() {
        mode = ToolGunMode.WELD
        selectedPropId = null
        selectedFace = null
        canUndo = false
    }
}
