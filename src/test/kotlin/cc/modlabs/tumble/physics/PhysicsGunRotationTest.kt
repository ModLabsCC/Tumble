// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PhysicsGunRotationTest {
    @Test
    fun `mouse rotation changes orientation and stays normalized`() {
        val rotated = PhysicsGunRotation.apply(PhysicsRotation.IDENTITY, 18.0, -9.0, 35.0)

        assertNotEquals(PhysicsRotation.IDENTITY, rotated)
        assertEquals(1.0, rotated.squaredLength(), 1e-10)
    }
}
