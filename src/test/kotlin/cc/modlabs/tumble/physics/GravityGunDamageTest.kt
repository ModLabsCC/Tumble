// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class GravityGunDamageTest {
    @Test
    fun `damage scales with hardness and remains capped`() {
        assertTrue(GravityGunDamage.damage(0.2f) < GravityGunDamage.damage(3f))
        assertEquals(20f, GravityGunDamage.damage(-1f))
        assertEquals(20f, GravityGunDamage.damage(100f))
    }

    @Test
    fun `swept cube catches fast targets without false side hits`() {
        val victim = PhysicsAabb(PhysicsVector(4.5, 0.0, -0.3), PhysicsVector(5.1, 1.8, 0.3))

        assertTrue(GravityGunDamage.sweptCubeHits(PhysicsVector.ZERO, PhysicsVector(10.0, 0.9, 0.0), victim))
        assertFalse(GravityGunDamage.sweptCubeHits(PhysicsVector.ZERO, PhysicsVector(10.0, 0.9, 4.0), victim))
    }
}
