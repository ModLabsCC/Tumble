// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MovingPlatformCarryTest {
    @Test
    fun `standing actor inherits platform displacement`() {
        val delta = PhysicsVector(0.4, 0.1, -0.2)

        assertEquals(
            delta,
            MovingPlatformCarry.displacement(
                box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
                box(-0.3, 0.5, -0.3, 0.3, 2.3, 0.3),
                delta
            )
        )
    }

    @Test
    fun `actor beside or below platform is not carried`() {
        val platform = box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
        val delta = PhysicsVector(0.4, 0.0, 0.0)

        assertNull(MovingPlatformCarry.displacement(platform, box(0.6, 0.5, -0.2, 1.2, 2.3, 0.2), delta))
        assertNull(MovingPlatformCarry.displacement(platform, box(-0.2, 0.2, -0.2, 0.2, 2.0, 0.2), delta))
    }

    @Test
    fun `unsafe teleport does not carry actor`() {
        assertNull(
            MovingPlatformCarry.displacement(
                box(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
                box(-0.3, 0.5, -0.3, 0.3, 2.3, 0.3),
                PhysicsVector(4.0, 0.0, 0.0)
            )
        )
    }

    private fun box(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) =
        PhysicsAabb(PhysicsVector(minX, minY, minZ), PhysicsVector(maxX, maxY, maxZ))
}
