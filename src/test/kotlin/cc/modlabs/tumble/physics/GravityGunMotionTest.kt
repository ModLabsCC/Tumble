// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GravityGunMotionTest {
    @Test
    fun `hold velocity pulls toward target and stays bounded`() {
        val nearby = GravityGunMotion.holdVelocity(PhysicsVector.ZERO, PhysicsVector(1.0, 0.0, 0.0))
        val distant = GravityGunMotion.holdVelocity(PhysicsVector.ZERO, PhysicsVector(100.0, 0.0, 0.0))

        assertEquals(10.0, nearby.x)
        assertTrue(distant.x > 0.0)
        assertEquals(30.0, distant.length(), 1e-9)
    }

    @Test
    fun `living entity hold velocity uses minecraft scale`() {
        val nearby = GravityGunMotion.livingHoldVelocity(PhysicsVector.ZERO, PhysicsVector(1.0, 0.0, 0.0))
        val distant = GravityGunMotion.livingHoldVelocity(PhysicsVector.ZERO, PhysicsVector(100.0, 0.0, 0.0))

        assertEquals(0.5, nearby.x)
        assertEquals(1.5, distant.length(), 1e-9)
    }

    @Test
    fun `gravity gun moves heavy props more slowly`() {
        val light = GravityGunMotion.holdVelocity(
            PhysicsVector.ZERO, PhysicsVector(1.0, 0.0, 0.0), mass = 1.0, strength = 10.0
        )
        val heavy = GravityGunMotion.holdVelocity(
            PhysicsVector.ZERO, PhysicsVector(1.0, 0.0, 0.0), mass = 20.0, strength = 10.0
        )

        assertEquals(10.0, light.x)
        assertEquals(2.0, heavy.x)
    }
}
