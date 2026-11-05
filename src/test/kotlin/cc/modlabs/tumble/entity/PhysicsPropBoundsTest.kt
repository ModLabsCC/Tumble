// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.entity

import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsAabb
import cc.modlabs.tumble.physics.PhysicsVector
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class PhysicsPropBoundsTest {
    @Test
    fun `identity cube has exact half block bounds`() {
        val half = PhysicsPropBounds.halfExtents(PhysicsRotation.IDENTITY)
        assertEquals(0.5, half.x, 0.000001)
        assertEquals(0.5, half.y, 0.000001)
        assertEquals(0.5, half.z, 0.000001)
    }

    @Test
    fun `quarter diagonal rotation expands only affected axes`() {
        val angle = Math.PI / 8.0
        val half = PhysicsPropBounds.halfExtents(PhysicsRotation(0.0, kotlin.math.sin(angle), 0.0, kotlin.math.cos(angle)))
        assertEquals(sqrt(0.5), half.x, 0.000001)
        assertEquals(0.5, half.y, 0.000001)
        assertEquals(sqrt(0.5), half.z, 0.000001)
    }

    @Test
    fun `slab bounds retain their local vertical offset`() {
        val bounds = PhysicsPropBounds.rotatedAabb(
            PhysicsRotation.IDENTITY,
            PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.0, 0.5))
        )

        assertEquals(-0.5, bounds.min.y, 0.000001)
        assertEquals(0.0, bounds.max.y, 0.000001)
    }
}
