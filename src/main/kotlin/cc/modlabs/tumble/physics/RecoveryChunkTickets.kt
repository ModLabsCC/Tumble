// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.util.IdentityHashMap
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos

/** Temporary and reference-counted: nested recovery must not remove another operation's ticket. */
internal object RecoveryChunkTickets {
    private val type = TicketType(Long.MAX_VALUE, TicketType.FLAG_LOADING or TicketType.FLAG_SIMULATION or TicketType.FLAG_KEEP_DIMENSION_ACTIVE)
    private val leases = IdentityHashMap<ServerLevel, MutableMap<ChunkPos, Int>>()

    fun acquire(level: ServerLevel, chunk: ChunkPos) {
        val counts = leases.getOrPut(level) { mutableMapOf() }
        val previous = counts[chunk] ?: 0
        if (previous == 0) level.chunkSource.addTicketWithRadius(type, chunk, 2)
        counts[chunk] = previous + 1
    }

    fun release(level: ServerLevel, chunk: ChunkPos) {
        val counts = leases[level] ?: return
        val previous = counts[chunk] ?: return
        if (previous == 1) {
            counts.remove(chunk)
            level.chunkSource.removeTicketWithRadius(type, chunk, 2)
            if (counts.isEmpty()) leases.remove(level)
        } else counts[chunk] = previous - 1
    }

    fun stopped(server: MinecraftServer) {
        leases.keys.removeIf { it.server === server }
    }
}
