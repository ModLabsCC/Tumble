// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client

import cc.modlabs.tumble.TumbleMod
import cc.modlabs.tumble.network.PhysicsDebugPayload
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft

object ClientPhysicsDebugHud {
    private var payload: PhysicsDebugPayload? = null
    private var receivedAt = 0L

    fun register() {
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.HELD_ITEM_TOOLTIP,
            TumbleMod.id("physics_debug_hud")
        ) { graphics, _ ->
            val state = payload ?: return@attachElementAfter
            if (System.nanoTime() - receivedAt > STALE_NANOS) return@attachElementAfter
            val client = Minecraft.getInstance()
            if (client.gui.screen() != null) return@attachElementAfter
            val runtime = Runtime.getRuntime()
            val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
            val lines = listOf(
                "Project Tumble Benchmark",
                "FPS ${client.fps} | Heap ${usedMemory} MB",
                "Props ${state.props}/${state.bodies} | Physics ${"%.2f".format(state.averageWorkMillis)} ms | Q ${state.queuedCommands}",
                "Net ${state.statesSent}/${state.payloadsSent} | Sleep omitted ${state.sleepingOmitted}"
            )
            val width = lines.maxOf(client.font::width) + 12
            val x = graphics.guiWidth() - width - 8
            val y = 8
            graphics.fill(x, y, x + width, y + lines.size * 11 + 8, 0xB0080C10.toInt())
            lines.forEachIndexed { index, line ->
                graphics.text(
                    client.font,
                    line,
                    x + 6,
                    y + 5 + index * 11,
                    if (index == 0) 0xFF55DDFF.toInt() else 0xFFFFFFFF.toInt(),
                    true
                )
            }
        }
    }

    fun receive(newPayload: PhysicsDebugPayload) {
        payload = newPayload
        receivedAt = System.nanoTime()
    }

    fun clear() {
        payload = null
        receivedAt = 0L
    }

    private const val STALE_NANOS = 2_500_000_000L
}
