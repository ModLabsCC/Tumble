// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathTypesTest {
    @Test
    fun `vectors validate and calculate`() {
        val value = PhysicsVector(1.0, 2.0, 2.0)

        assertEquals(9.0, value.squaredLength())
        assertEquals(3.0, value.length())
        assertEquals(PhysicsVector(2.0, 4.0, 4.0), value * 2.0)
        assertFailsWith<IllegalArgumentException> { PhysicsVector(Double.NaN, 0.0, 0.0) }
    }

    @Test
    fun `quaternions normalize and reject invalid values`() {
        val normalized = PhysicsRotation(0.0, sqrt(0.5) * 2.0, 0.0, sqrt(0.5) * 2.0).normalized()

        assertEquals(1.0, normalized.squaredLength(), 1e-12)
        assertFailsWith<IllegalArgumentException> { PhysicsRotation(0.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PhysicsRotation(Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0) }
    }

    @Test
    fun `aabbs expand and overlap`() {
        val first = PhysicsAabb(PhysicsVector.ZERO, PhysicsVector(1.0, 1.0, 1.0))
        val second = PhysicsAabb(PhysicsVector(1.5, 0.0, 0.0), PhysicsVector(2.0, 1.0, 1.0))

        assertFalse(first.overlaps(second))
        assertTrue(first.expanded(0.5).overlaps(second))
        assertFailsWith<IllegalArgumentException> { first.expanded(-1.0) }
    }

    @Test
    fun `section mapping uses floor division at boundaries`() {
        assertEquals(0, PhysicsBlockPosition(0, 0, 0).section().x)
        assertEquals(0, PhysicsBlockPosition(15, 0, 0).section().x)
        assertEquals(1, PhysicsBlockPosition(16, 0, 0).section().x)
        assertEquals(-1, PhysicsBlockPosition(-1, 0, 0).section().x)
        assertEquals(-1, PhysicsBlockPosition(-16, 0, 0).section().x)
        assertEquals(-2, PhysicsBlockPosition(-17, 0, 0).section().x)
        assertEquals(-1, PhysicsBlockPosition(0, -1, -16).section().y)
        assertEquals(-1, PhysicsBlockPosition(0, -1, -16).section().z)
    }
}
