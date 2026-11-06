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
    private data class Lease(var count: Int, var loaded: CompletableFuture<*>? = null)
    private val leases = IdentityHashMap<ServerLevel, MutableMap<ChunkPos, Lease>>()

    /** Adds a ticket without waiting; the caller must check entity visibility in a later tick. */
    fun request(level: ServerLevel, chunk: ChunkPos) {
        val counts = leases.getOrPut(level) { mutableMapOf() }
        val lease = counts[chunk] ?: run {
            level.chunkSource.addTicketWithRadius(type, chunk, 2)
            Lease(0).also { counts[chunk] = it }
        }
        lease.count++
    }

    /** Waits while pumping server tasks; callers must fence and revalidate mutable state. */
    fun acquire(level: ServerLevel, chunk: ChunkPos) = acquire(level, chunk, requireTicking = true)

    // Startup replay runs before the server can tick entities; loaded data is sufficient there.
    fun acquireForRecovery(level: ServerLevel, chunk: ChunkPos) = acquire(level, chunk, requireTicking = false)

    private fun acquire(level: ServerLevel, chunk: ChunkPos, requireTicking: Boolean) {
        request(level, chunk)
        try {
            val lease = leases.getValue(level).getValue(chunk)
            val loaded = lease.loaded ?: level.chunkSource.addTicketAndLoadWithRadius(type, chunk, 2).also { lease.loaded = it }
            level.server.managedBlock { loaded.isDone }
            loaded.join()
            level.waitForEntities(chunk, 0)
            check(level.areEntitiesLoaded(chunk.pack())) { "Recovery chunk entities are not loaded: $chunk" }
            if (requireTicking) check(level.areEntitiesActuallyLoadedAndTicking(chunk)) {
                "Recovery chunk entities are not yet ticking: $chunk"
            }
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
