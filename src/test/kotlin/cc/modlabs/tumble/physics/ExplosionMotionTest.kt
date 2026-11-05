// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExplosionMotionTest {
    @Test
    fun `explosion velocity falls off and stays inside radius`() {
        val origin = PhysicsVector.ZERO

        assertEquals(PhysicsVector(0.0, 20.0, 0.0), ExplosionMotion.velocity(origin, origin))
        assertEquals(2.0, ExplosionMotion.velocity(origin, PhysicsVector(4.0, 0.0, 0.0), 5.0, 10.0)!!.x, 1e-9)
        assertNull(ExplosionMotion.velocity(origin, PhysicsVector(8.0, 0.0, 0.0)))
    }
}
