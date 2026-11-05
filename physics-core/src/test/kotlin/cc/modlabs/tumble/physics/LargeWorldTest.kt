// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.box3d.global.Box3D.b3IsDoublePrecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LargeWorldTest {
    @Test
    fun `native positions keep sub-block detail at both world borders`() {
        assertTrue(b3IsDoublePrecision(), "Tumble requires double-precision native world positions")
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
            for (x in listOf(30_000_000.125, -30_000_000.125)) {
                val body = world.createBody(bodyAt(x, 10.0, 0.0))
                repeat(10) { world.step() }
                assertEquals(x, assertNotNull(world.snapshot(body)).transform.position.x, 0.00001)
                world.destroyBody(body)
            }
        }
    }
    @Test
    fun `partial terrain supports bodies at both world borders`() {
        for (x in listOf(29_999_984, -29_999_984)) {
            PhysicsWorld().use { world ->
                world.syncTerrain(listOf(TerrainBlockGeometry(
                    PhysicsBlockPosition(x, 0, 0),
                    listOf(TerrainBox(PhysicsBox(
                        PhysicsVector(x + 0.25, 0.0, 0.25),
                        PhysicsVector(x + 0.75, 0.5, 0.75)
                    )))
                )))
                val body = world.createBody(bodyAt(x + 0.5, 4.0, 0.5, shape = PhysicsShapeDefinition.Box(PhysicsBox.centered(0.2, 1.0, 0.2))))
                val miss = world.createBody(bodyAt(x + 0.05, 4.0, 0.5, shape = PhysicsShapeDefinition.Box(PhysicsBox.centered(0.2, 1.0, 0.2))))
                repeat(360) { world.step() }
                assertTrue(assertNotNull(world.snapshot(miss)).transform.position.y < -2.0, "A body outside the narrow slab should fall")
                val snapshot = assertNotNull(world.snapshot(body))
                assertTrue(snapshot.transform.position.y in 0.95..1.05,
                    "Body fell through terrain at x=$x: y=${snapshot.transform.position.y}")
            }
        }
    }
}
