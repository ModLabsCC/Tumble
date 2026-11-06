// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.entity.PhysicsPropBounds
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sqrt
import net.minecraft.world.phys.AABB

data class AssemblyTerrainGuard(val bounds: List<PhysicsAabb>, val minSectionY: Int, val maxSectionY: Int) {
    init {
        require(bounds.isNotEmpty() && bounds.size <= 512 && minSectionY < maxSectionY) { "Invalid assembly terrain guard" }
    }
}

internal object AssemblyTerrainCoverage {
    const val MAX_SECTIONS = 2048
    private const val LOOKAHEAD = 0.1
    private const val MAX_TRANSLATION = 64.0

    fun sections(
        guard: AssemblyTerrainGuard,
        transform: PhysicsTransform,
        velocity: PhysicsVector,
        angular: PhysicsVector,
        horizontalPadding: Int = 0,
        verticalPadding: Int = 0,
        below: Int = 1
    ): Set<PhysicsSectionPosition>? = calculate(guard, transform, velocity, angular, horizontalPadding, verticalPadding, below)?.sections

    data class Result(val sections: Set<PhysicsSectionPosition>, val visits: Int)

    fun calculate(
        guard: AssemblyTerrainGuard,
        transform: PhysicsTransform,
        velocity: PhysicsVector,
        angular: PhysicsVector,
        horizontalPadding: Int = 0,
        verticalPadding: Int = 0,
        below: Int = 1,
        limit: Int = MAX_SECTIONS,
        target: PhysicsTransform? = null
    ): Result? {
        if (listOf(transform.position.x, transform.position.y, transform.position.z).any { abs(it) > 30_001_024.0 }) return null
        val dx = velocity.x * LOOKAHEAD
        val dy = velocity.y * LOOKAHEAD
        val dz = velocity.z * LOOKAHEAD
        if (listOf(dx, dy, dz).any { !it.isFinite() || abs(it) > MAX_TRANSLATION }) return null
        val speed = angular.length()
        if (!speed.isFinite()) return null
        val axis = if (speed > 0) angular * (1.0 / speed) else PhysicsVector.ZERO
        val targetDelta = target?.let { it.position - transform.position } ?: PhysicsVector.ZERO
        if (listOf(targetDelta.x, targetDelta.y, targetDelta.z).any { abs(it) > MAX_TRANSLATION }) return null
        val rotation = transform.rotation.normalized()
        val targetRotation = target?.rotation?.normalized() ?: rotation
        val dot = abs(rotation.x * targetRotation.x + rotation.y * targetRotation.y + rotation.z * targetRotation.z + rotation.w * targetRotation.w)
        val maximumRotation = PhysicsWorldConfig.MAX_TERRAIN_STEP_ROTATION + 2.0 * acos(dot.coerceIn(0.0, 1.0))
        val positions = linkedSetOf<PhysicsSectionPosition>()
        var remaining = minOf(limit, MAX_SECTIONS)
        val globalMinX = minOf(0.0, guard.bounds.minOf { it.min.x }); val globalMaxX = maxOf(0.0, guard.bounds.maxOf { it.max.x })
        val globalMinY = minOf(0.0, guard.bounds.minOf { it.min.y }); val globalMaxY = maxOf(0.0, guard.bounds.maxOf { it.max.y })
        val globalMinZ = minOf(0.0, guard.bounds.minOf { it.min.z }); val globalMaxZ = maxOf(0.0, guard.bounds.maxOf { it.max.z })
        for (local in guard.bounds) {
            val rotated = PhysicsPropBounds.rotatedAabb(transform.rotation, local)
            val point = transform.position
            var bounds = AABB(point.x + rotated.min.x, point.y + rotated.min.y, point.z + rotated.min.z,
                point.x + rotated.max.x, point.y + rotated.max.y, point.z + rotated.max.z)
            if (speed > 0) {
                var radiusSquared = 0.0
                for (x in listOf(rotated.min.x, rotated.max.x)) for (y in listOf(rotated.min.y, rotated.max.y)) for (z in listOf(rotated.min.z, rotated.max.z)) {
                    val along = x * axis.x + y * axis.y + z * axis.z
                    radiusSquared = maxOf(radiusSquared, x*x + y*y + z*z - along*along)
                }
                val distance = sqrt(radiusSquared.coerceAtLeast(0.0)) * minOf(2.0, speed * LOOKAHEAD)
                bounds = bounds.inflate(distance * sqrt((1 - axis.x * axis.x).coerceAtLeast(0.0)),
                    distance * sqrt((1 - axis.y * axis.y).coerceAtLeast(0.0)),
                    distance * sqrt((1 - axis.z * axis.z).coerceAtLeast(0.0)))
            }
            // Dynamic COM lies inside the shape bounds; static and kinematic COM is the origin.
            val rx = maxOf(abs(local.min.x - globalMaxX), abs(local.max.x - globalMinX))
            val ry = maxOf(abs(local.min.y - globalMaxY), abs(local.max.y - globalMinY))
            val rz = maxOf(abs(local.min.z - globalMaxZ), abs(local.max.z - globalMinZ))
            val worstStep = sqrt(rx*rx + ry*ry + rz*rz) * minOf(2.0, maximumRotation) +
                PhysicsWorldConfig.MAX_TERRAIN_STEP_TRANSLATION
            bounds = bounds.inflate(worstStep)
            bounds = bounds.expandTowards(targetDelta.x, targetDelta.y, targetDelta.z)
            bounds = bounds.expandTowards(dx, dy, dz)
            val minX = floor(bounds.minX / 16).toInt() - horizontalPadding
            val maxX = floor(bounds.maxX / 16).toInt() + horizontalPadding
            val minY = maxOf(guard.minSectionY, floor(bounds.minY / 16).toInt() - verticalPadding - below)
            val maxY = minOf(guard.maxSectionY - 1, floor(bounds.maxY / 16).toInt() + verticalPadding)
            val minZ = floor(bounds.minZ / 16).toInt() - horizontalPadding
            val maxZ = floor(bounds.maxZ / 16).toInt() + horizontalPadding
            if (minY > maxY) continue
            val count = (maxX.toLong() - minX + 1) * (maxY.toLong() - minY + 1) * (maxZ.toLong() - minZ + 1)
            if (count > remaining) return null
            remaining -= count.toInt()
            for (x in minX..maxX) for (z in minZ..maxZ) for (y in minY..maxY) {
                positions += PhysicsSectionPosition(x, y, z)
            }
        }
        return Result(positions, minOf(limit, MAX_SECTIONS) - remaining)
    }
}
