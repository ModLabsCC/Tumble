// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos

/** Temporary and reference-counted: nested recovery must not remove another operation's ticket. */
internal object RecoveryChunkTickets {
    private val type = TicketType(Long.MAX_VALUE, TicketType.FLAG_LOADING or TicketType.FLAG_SIMULATION or TicketType.FLAG_KEEP_DIMENSION_ACTIVE)
    private data class Lease(var count: Int, val loaded: CompletableFuture<*>)
    private val leases = IdentityHashMap<ServerLevel, MutableMap<ChunkPos, Lease>>()

    fun acquire(level: ServerLevel, chunk: ChunkPos) {
        val counts = leases.getOrPut(level) { mutableMapOf() }
        val lease = counts[chunk] ?: Lease(0, level.chunkSource.addTicketAndLoadWithRadius(type, chunk, 2)).also { counts[chunk] = it }
        lease.count++
        try {
            // This pumps server tasks like ServerLevel.waitForEntities; joining directly here can deadlock.
            level.server.managedBlock { lease.loaded.isDone }
            lease.loaded.join()
            level.waitForEntities(chunk, 0)
            check(level.areEntitiesActuallyLoadedAndTicking(chunk)) { "Recovery chunk entities are not yet ticking: $chunk" }
        } catch (error: Exception) {
            release(level, chunk)
            throw error
        }
    }

    fun release(level: ServerLevel, chunk: ChunkPos) {
        val counts = leases[level] ?: return
        val lease = counts[chunk] ?: return
        if (--lease.count == 0) {
            counts.remove(chunk)
            level.chunkSource.removeTicketWithRadius(type, chunk, 2)
            if (counts.isEmpty()) leases.remove(level)
        }
    }

    fun stopped(server: MinecraftServer) {
        leases.keys.removeIf { it.server === server }
    }
}
