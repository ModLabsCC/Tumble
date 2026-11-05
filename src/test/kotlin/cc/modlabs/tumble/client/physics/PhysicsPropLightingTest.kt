// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.physics.PhysicsRotation
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minecraft.world.level.CardinalLighting

class PhysicsPropLightingTest {
    private val base = CardinalLighting(0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f)

    @Test
    fun `identity rotation preserves vanilla cardinal shading`() {
        assertEquals(base, PhysicsPropLighting.rotate(base, PhysicsRotation.IDENTITY))
    }

    @Test
    fun `rotated local faces receive their new world direction shading`() {
        val quarterTurnY = PhysicsRotation(0.0, sqrt(0.5), 0.0, sqrt(0.5))
        val lighting = PhysicsPropLighting.rotate(base, quarterTurnY)

        assertEquals(0.6f, lighting.north(), 0.0001f)
        assertEquals(0.6f, lighting.south(), 0.0001f)
        assertEquals(0.8f, lighting.west(), 0.0001f)
        assertEquals(0.8f, lighting.east(), 0.0001f)
        assertEquals(1.0f, lighting.up(), 0.0001f)
        assertEquals(0.5f, lighting.down(), 0.0001f)
    }

}
