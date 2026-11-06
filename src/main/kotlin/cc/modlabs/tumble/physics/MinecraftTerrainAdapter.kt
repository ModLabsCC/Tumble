// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.physics.PhysicsBlockPosition
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsMaterial
import cc.modlabs.tumble.physics.PhysicsSectionPosition
import cc.modlabs.tumble.physics.PhysicsShapeDefinition
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.TerrainBlockGeometry
import cc.modlabs.tumble.physics.TerrainBox
import java.util.ArrayDeque
import java.util.LinkedHashSet
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.AABB
import kotlin.math.floor

data class TerrainSectionKey(
    val dimension: PhysicsDimensionId,
    val position: PhysicsSectionPosition
) {
    companion object {
        fun fromBlock(dimension: PhysicsDimensionId, x: Int, y: Int, z: Int) = TerrainSectionKey(
            dimension,
            PhysicsBlockPosition(x, y, z).section()
        )
    }
}

class DirtySectionQueue {
    private val urgent = LinkedHashSet<TerrainSectionKey>()
    private val sections = LinkedHashSet<TerrainSectionKey>()

    fun add(section: TerrainSectionKey) = section in urgent || sections.add(section)

    fun prioritize(section: TerrainSectionKey) {
        sections.remove(section)
        urgent.add(section)
    }

    fun promote(section: TerrainSectionKey) {
        if (sections.remove(section)) urgent.add(section)
    }

    fun remove(section: TerrainSectionKey) = urgent.remove(section) || sections.remove(section)
    fun size() = urgent.size + sections.size
    fun contains(section: TerrainSectionKey) = section in urgent || section in sections

    fun take(limit: Int): List<TerrainSectionKey> {
        require(limit >= 0) { "Dirty section limit must be non-negative" }
        val result = ArrayList<TerrainSectionKey>(minOf(limit, size()))
        for (source in listOf(urgent, sections)) {
            val iterator = source.iterator()
            while (iterator.hasNext() && result.size < limit) {
                result += iterator.next()
                iterator.remove()
            }
        }
        return result
    }
}

object MinecraftTerrainAdapter {
    data class BlockPhysicsData(
        val material: PhysicsMaterial,
        val buoyancy: PhysicsBuoyancy = PhysicsBuoyancy()
    )

    fun dynamicShape(boxes: List<AABB>): PhysicsShapeDefinition? {
        if (boxes.size > MAX_DYNAMIC_SHAPE_BOXES) return null
        val centered = boxes.mapNotNull { box ->
            if (box.maxX <= box.minX || box.maxY <= box.minY || box.maxZ <= box.minZ) return@mapNotNull null
            PhysicsBox(
                PhysicsVector(box.minX - 0.5, box.minY - 0.5, box.minZ - 0.5),
                PhysicsVector(box.maxX - 0.5, box.maxY - 0.5, box.maxZ - 0.5)
            )
        }
        return when (centered.size) {
            0 -> null
            1 -> PhysicsShapeDefinition.Box(centered.single())
            else -> PhysicsShapeDefinition.CompoundBoxes(centered)
        }
    }

    fun extractSection(level: ServerLevel, section: PhysicsSectionPosition): List<TerrainBlockGeometry> {
        val minX = section.x * 16
        val minY = section.y * 16
        val minZ = section.z * 16
        val geometry = ArrayList<TerrainBlockGeometry>()
        for (y in maxOf(minY, level.minY)..<minOf(minY + 16, level.maxY)) {
            for (z in minZ..<minZ + 16) {
                for (x in minX..<minX + 16) {
                    val position = BlockPos(x, y, z)
                    val state = level.getBlockState(position)
                    val boxes = boxesAt(position, state.getCollisionShape(level, position).toAabbs(), material(state))
                    if (boxes.isNotEmpty()) {
                        geometry += TerrainBlockGeometry(PhysicsBlockPosition(x, y, z), boxes)
                    }
                }
            }
        }
        return geometry
    }

    fun boxesAt(position: BlockPos, boxes: List<AABB>, material: PhysicsMaterial): List<TerrainBox> = boxes.map { box ->
        TerrainBox(
            PhysicsBox(
                PhysicsVector(position.x + box.minX, position.y + box.minY, position.z + box.minZ),
                PhysicsVector(position.x + box.maxX, position.y + box.maxY, position.z + box.maxZ)
            ),
            material
        )
    }

    fun physicsData(state: BlockState): BlockPhysicsData = when {
        state.`is`(BlockTags.LEAVES) -> BlockPhysicsData(
            PhysicsMaterial(density = 0.15f), PhysicsBuoyancy(true, 2.4f, 0.2f, 0.2f)
        )
        state.`is`(BlockTags.BAMBOO_BLOCKS) -> BlockPhysicsData(
            PhysicsMaterial(density = 0.45f), PhysicsBuoyancy(true, 1.8f, 0.18f, 0.18f)
        )
        state.`is`(BlockTags.WOOL) || state.`is`(BlockTags.WOOL_CARPETS) -> BlockPhysicsData(
            PhysicsMaterial(density = 0.35f), PhysicsBuoyancy(true, 1.5f, 0.22f, 0.22f)
        )
        state.block === Blocks.ICE || state.block === Blocks.PACKED_ICE || state.block === Blocks.BLUE_ICE ->
            BlockPhysicsData(PhysicsMaterial.LOW_FRICTION.copy(density = 0.9f), PhysicsBuoyancy(true, 0.9f, 0.14f, 0.14f))
        state.`is`(BlockTags.MINEABLE_WITH_AXE) -> BlockPhysicsData(
            PhysicsMaterial(density = 0.65f), PhysicsBuoyancy(true, 1.8f, 0.18f, 0.18f, 0.08f)
        )
        state.block === Blocks.SAND || state.block === Blocks.RED_SAND -> BlockPhysicsData(
            PhysicsMaterial(density = 1.6f), PhysicsBuoyancy(true, 0.45f, 0.12f, 0.16f)
        )
        state.block === Blocks.GRAVEL -> BlockPhysicsData(
            PhysicsMaterial(density = 1.8f), PhysicsBuoyancy(true, 0.4f, 0.11f, 0.15f)
        )
        state.block === Blocks.FROSTED_ICE -> BlockPhysicsData(PhysicsMaterial.LOW_FRICTION.copy(density = 0.9f))
        state.block === Blocks.SLIME_BLOCK -> BlockPhysicsData(PhysicsMaterial.HIGH_BOUNCE.copy(density = 1.2f))
        state.block === Blocks.HONEY_BLOCK -> BlockPhysicsData(PhysicsMaterial(friction = 2f, rollingResistance = 1f, density = 1.4f))
        state.block === Blocks.RAW_COPPER_BLOCK || state.block in Blocks.COPPER_BLOCK.asList() -> BlockPhysicsData(PhysicsMaterial(density = 8.9f))
        state.block === Blocks.RAW_IRON_BLOCK || state.block === Blocks.IRON_BLOCK -> BlockPhysicsData(PhysicsMaterial(density = 7.8f))
        state.block === Blocks.RAW_GOLD_BLOCK || state.block === Blocks.GOLD_BLOCK -> BlockPhysicsData(PhysicsMaterial(density = 19.3f))
        else -> BlockPhysicsData(PhysicsMaterial.DEFAULT)
    }

    fun material(state: BlockState) = physicsData(state).material
    fun buoyancy(state: BlockState) = physicsData(state).buoyancy
    fun hasWaterResponse(state: BlockState) = buoyancy(state).enabled

    fun submersion(level: ServerLevel, bounds: AABB): Float {
        val volume = bounds.xsize * bounds.ysize * bounds.zsize
        if (volume <= 0.0) return 0f
        var submerged = 0.0
        for (y in maxOf(level.minY, floor(bounds.minY).toInt())..minOf(level.maxY - 1, floor(bounds.maxY - 1e-7).toInt())) {
            for (z in floor(bounds.minZ).toInt()..floor(bounds.maxZ - 1e-7).toInt()) {
                for (x in floor(bounds.minX).toInt()..floor(bounds.maxX - 1e-7).toInt()) {
                    val position = BlockPos(x, y, z)
                    val fluid = level.getFluidState(position)
                    if (fluid.`is`(FluidTags.WATER)) submerged += overlapVolume(
                        bounds,
                        AABB(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0,
                            y + fluid.getHeight(level, position).toDouble(), z + 1.0)
                    )
                }
            }
        }
        return (submerged / volume).coerceIn(0.0, 1.0).toFloat()
    }

    private const val MAX_DYNAMIC_SHAPE_BOXES = 64
}

internal fun verticalOverlap(bottom: Double, top: Double, fluidBottom: Double, fluidTop: Double) =
    (minOf(top, fluidTop) - maxOf(bottom, fluidBottom)).coerceAtLeast(0.0)

internal fun overlapVolume(first: AABB, second: AABB) =
    verticalOverlap(first.minX, first.maxX, second.minX, second.maxX) *
        verticalOverlap(first.minY, first.maxY, second.minY, second.maxY) *
        verticalOverlap(first.minZ, first.maxZ, second.minZ, second.maxZ)

class TerrainSectionTracker(private val service: ServerPhysicsService) {
    private val dirty = DirtySectionQueue()
    private val synced = mutableSetOf<TerrainSectionKey>()
    private val stale = mutableSetOf<TerrainSectionKey>()
    private val geometryCache = mutableMapOf<TerrainSectionKey, List<TerrainBlockGeometry>>()
    private val terrainRevisions = mutableMapOf<TerrainSectionKey, Long>()

    fun markBlockChanged(level: ServerLevel, position: BlockPos) {
        val dimension = level.physicsDimensionId()
        val nearby = listOf(position) + net.minecraft.core.Direction.entries.map(position::relative)
        nearby.mapTo(mutableSetOf()) { TerrainSectionKey.fromBlock(dimension, it.x, it.y, it.z) }.forEach { key ->
            if (synced.remove(key)) {
                stale.add(key)
                dirty.add(key)
                geometryCache.remove(key)
                terrainRevisions.remove(key)
                service.submit(PhysicsCommand.InvalidateTerrainSection(key.dimension, key.position))
            }
        }
    }

    fun chunkUnloaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val dimension = level.physicsDimensionId()
        val removed = (synced + stale).filterTo(ArrayDeque()) {
            it.dimension == dimension && it.position.x == chunkX && it.position.z == chunkZ
        }
        removed.forEach { key ->
            synced.remove(key)
            stale.remove(key)
            dirty.remove(key)
            geometryCache.remove(key)
            terrainRevisions.remove(key)
            service.submit(PhysicsCommand.InvalidateTerrainSection(key.dimension, key.position))
            service.submit(PhysicsCommand.RemoveTerrainSection(key.dimension, key.position))
        }
    }

    fun chunkLoaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        val dimension = level.physicsDimensionId()
        for (sectionY in level.minSectionY..<level.maxSectionY) {
            dirty.add(TerrainSectionKey(dimension, PhysicsSectionPosition(chunkX, sectionY, chunkZ)))
        }
    }

    fun tick(server: MinecraftServer, props: Collection<PhysicsPropEntity>) {
        val levels = server.allLevels.associateBy(ServerLevel::physicsDimensionId)
        levels.forEach { (dimension, level) -> refreshDesired(level, dimension, props.filter { it.level() === level }) }
        props.forEach { prop ->
            val level = prop.level() as? ServerLevel ?: return@forEach
            val dimension = level.physicsDimensionId()
            forEachCoveredSection(prop, horizontalPadding = 0, verticalPadding = 0, below = 1) { position ->
                if (position.y in level.minSectionY..<level.maxSectionY && level.chunkSource.hasChunk(position.x, position.z)) {
                    val key = TerrainSectionKey(dimension, position)
                    if (key in synced) dirty.promote(key) else dirty.prioritize(key)
                }
            }
        }
        dirty.take(MAX_DIRTY_SECTIONS_PER_TICK).forEach { key ->
            val level = levels[key.dimension] ?: return@forEach
            if (!level.chunkSource.hasChunk(key.position.x, key.position.z)) return@forEach
            val geometry = MinecraftTerrainAdapter.extractSection(level, key.position)
            if (geometryCache[key] == geometry) return@forEach
            val revision = service.nextTerrainRevision()
            if (service.submit(PhysicsCommand.ReplaceTerrainSection(key.dimension, key.position, geometry.toList(), revision))) {
                stale.remove(key)
                geometryCache[key] = geometry
                terrainRevisions[key] = revision
                synced += key
            } else {
                dirty.add(key)
            }
        }
    }

    fun syncedSectionCount() = synced.size
    fun dirtySectionCount() = dirty.size()

    fun coveredChunks(prop: PhysicsPropEntity): Set<Long> {
        val chunks = mutableSetOf<Long>()
        forEachCoveredSection(prop, horizontalPadding = 0, verticalPadding = 0, below = 1) {
            chunks += ChunkPos.pack(it.x, it.z)
        }
        return chunks
    }

    fun coverageWithinBudget(prop: PhysicsPropEntity): Boolean =
        forEachCoveredSection(prop, 0, 0, 1) { }

    fun assemblyTerrainReady(level: ServerLevel, prop: PhysicsPropEntity): Boolean {
        val dimension = level.physicsDimensionId()
        var ready = true
        val bounded = forEachCoveredSection(prop, 0, 0, 1) { position ->
            val key = TerrainSectionKey(dimension, position)
            if (!level.chunkSource.hasChunk(position.x, position.z) || key !in synced || dirty.contains(key) ||
                (terrainRevisions[key] ?: Long.MAX_VALUE) > service.appliedTerrainRevision()) ready = false
        }
        return bounded && ready
    }

    fun primeForProp(level: ServerLevel, position: BlockPos) {
        primeForPositions(level, listOf(position))
    }

    fun primeForAssembly(level: ServerLevel, prop: PhysicsPropEntity, allowQueued: Boolean = false): Boolean {
        val positions = linkedSetOf<PhysicsSectionPosition>()
        val bounded = forEachCoveredSection(prop, horizontalPadding = 0, verticalPadding = 0, below = 1) { position ->
            if (position.y in level.minSectionY..<level.maxSectionY) positions += position
        }
        if (!bounded) return false
        if (positions.any { !level.chunkSource.hasChunk(it.x, it.z) }) return false
        val dimension = level.physicsDimensionId()
        var remaining = if (allowQueued) MAX_INITIAL_SECTIONS_PER_TICK else MAX_DIRTY_SECTIONS_PER_TICK
        var pending = false
        positions.forEach { position ->
            val key = TerrainSectionKey(dimension, position)
            if (key in synced && !dirty.contains(key)) {
                if ((terrainRevisions[key] ?: Long.MAX_VALUE) > service.appliedTerrainRevision()) pending = true
                return@forEach
            }
            if (remaining-- <= 0) return false
            val geometry = MinecraftTerrainAdapter.extractSection(level, position)
            if (key in synced && geometryCache[key] == geometry) {
                dirty.remove(key)
                if ((terrainRevisions[key] ?: Long.MAX_VALUE) > service.appliedTerrainRevision()) pending = true
                return@forEach
            }
            val revision = service.nextTerrainRevision()
            if (!service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, position, geometry.toList(), revision))) return false
            stale.remove(key)
            geometryCache[key] = geometry
            terrainRevisions[key] = revision
            synced += key
            dirty.remove(key)
            pending = true
        }
        return allowQueued || !pending
    }

    fun primeForPositions(level: ServerLevel, positions: Collection<BlockPos>) {
        val dimension = level.physicsDimensionId()
        positions.flatMapTo(linkedSetOf()) { position ->
            val section = PhysicsBlockPosition(position.x, position.y, position.z).section()
            listOf(section, PhysicsSectionPosition(section.x, section.y - 1, section.z))
        }.forEach { sectionPosition ->
            val key = TerrainSectionKey(dimension, sectionPosition)
            val geometry = MinecraftTerrainAdapter.extractSection(level, sectionPosition)
            val revision = service.nextTerrainRevision()
            if (service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, sectionPosition, geometry.toList(), revision))) {
                stale.remove(key)
                geometryCache[key] = geometry
                terrainRevisions[key] = revision
                synced += key
                dirty.remove(key)
            }
        }
    }

    private fun refreshDesired(
        level: ServerLevel,
        dimension: PhysicsDimensionId,
        props: List<PhysicsPropEntity>
    ) {
        val desired = mutableSetOf<TerrainSectionKey>()
        var complete = true
        props.forEach { prop ->
            if (!forEachCoveredSection(prop, TERRAIN_CHUNK_RADIUS, TERRAIN_SECTION_RADIUS, below = 0) { position ->
                if (position.y in level.minSectionY..<level.maxSectionY && level.chunkSource.hasChunk(position.x, position.z)) {
                    desired += TerrainSectionKey(dimension, position)
                }
            }) {
                val bounded = forEachCoveredSection(prop, 0, 0, below = 1) { position ->
                    if (level.chunkSource.hasChunk(position.x, position.z)) desired += TerrainSectionKey(dimension, position)
                }
                val body = prop.runtimeBodyId
                if (!bounded && body != null && service.latestFrame(dimension).bodies[body]?.suspended != true) complete = false
            }
        }

        (synced + stale).filter { complete && it.dimension == dimension && it !in desired }.forEach { key ->
            synced.remove(key)
            stale.remove(key)
            dirty.remove(key)
            geometryCache.remove(key)
            terrainRevisions.remove(key)
            service.submit(PhysicsCommand.RemoveTerrainSection(key.dimension, key.position))
        }
        desired.filter { it !in synced }.forEach(dirty::add)
    }

    private fun forEachCoveredSection(
        prop: PhysicsPropEntity,
        horizontalPadding: Int,
        verticalPadding: Int,
        below: Int,
        action: (PhysicsSectionPosition) -> Unit
    ): Boolean {
        var remaining = MAX_COVERAGE_SECTIONS
        val structure = prop.assembly
        if (structure == null) {
            return forEachSectionInBounds(prop.boundingBox, prop, horizontalPadding, verticalPadding, below, remaining, action) >= 0
        }
        val level = prop.level() as? ServerLevel ?: return false
        val sections = AssemblyTerrainCoverage.sections(
            AssemblyTerrainGuard(structure.coverageBounds, level.minSectionY, level.maxSectionY),
            PhysicsTransform(PhysicsVector(prop.x, prop.y, prop.z), prop.bodyRotation),
            PhysicsVector(prop.deltaMovement.x, prop.deltaMovement.y, prop.deltaMovement.z), prop.angularVelocity,
            horizontalPadding, verticalPadding, below
        ) ?: return false
        sections.forEach(action)
        return true
    }

    private fun forEachSectionInBounds(
        initialBounds: AABB,
        prop: PhysicsPropEntity,
        horizontalPadding: Int,
        verticalPadding: Int,
        below: Int,
        remaining: Int,
        action: (PhysicsSectionPosition) -> Unit
    ): Int {
        val velocity = prop.deltaMovement
        val bounds = initialBounds.expandTowards(
            (velocity.x * TERRAIN_LOOKAHEAD_SECONDS).coerceIn(-MAX_TERRAIN_LOOKAHEAD, MAX_TERRAIN_LOOKAHEAD),
            (velocity.y * TERRAIN_LOOKAHEAD_SECONDS).coerceIn(-MAX_TERRAIN_LOOKAHEAD, MAX_TERRAIN_LOOKAHEAD),
            (velocity.z * TERRAIN_LOOKAHEAD_SECONDS).coerceIn(-MAX_TERRAIN_LOOKAHEAD, MAX_TERRAIN_LOOKAHEAD)
        )
        val minX = floor(bounds.minX / 16.0).toInt() - horizontalPadding
        val maxX = floor(bounds.maxX / 16.0).toInt() + horizontalPadding
        val level = prop.level() as? ServerLevel ?: return -1
        val minY = maxOf(level.minSectionY, floor(bounds.minY / 16.0).toInt() - verticalPadding - below)
        val maxY = minOf(level.maxSectionY - 1, floor(bounds.maxY / 16.0).toInt() + verticalPadding)
        val minZ = floor(bounds.minZ / 16.0).toInt() - horizontalPadding
        val maxZ = floor(bounds.maxZ / 16.0).toInt() + horizontalPadding
        if (minY > maxY) return 0
        val count = (maxX.toLong() - minX + 1) * (maxY.toLong() - minY + 1) * (maxZ.toLong() - minZ + 1)
        if (count > remaining) return -1
        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                for (y in minY..maxY) {
                    action(PhysicsSectionPosition(x, y, z))
                }
            }
        }
        return count.toInt()
    }

    private companion object {
        const val TERRAIN_CHUNK_RADIUS = 1
        const val TERRAIN_SECTION_RADIUS = 1
        const val TERRAIN_LOOKAHEAD_SECONDS = 0.1
        const val MAX_TERRAIN_LOOKAHEAD = 64.0
        const val MAX_DIRTY_SECTIONS_PER_TICK = 2
        const val MAX_INITIAL_SECTIONS_PER_TICK = 64
        const val MAX_COVERAGE_SECTIONS = 2048
    }
}

fun ServerLevel.physicsDimensionId() = PhysicsDimensionId(dimension().identifier().toString())
