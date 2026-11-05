// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import net.minecraft.core.Direction
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolGunFaceTest {
    @Test
    fun `ray selects the entered prop face`() {
        assertEquals(
            Direction.EAST,
            ToolGunFace.fromRay(
                PhysicsRotation.IDENTITY,
                PhysicsVector(2.0, 0.1, 0.0),
                PhysicsVector(0.0, 0.1, 0.0)
            )
        )
    }
}
