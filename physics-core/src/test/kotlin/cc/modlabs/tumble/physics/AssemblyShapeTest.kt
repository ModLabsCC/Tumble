// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssemblyShapeTest {
    @Test
    fun `a 250 block staircase can be simulated as one rigid body`() = PhysicsWorld().use { world ->
        val boxes = (0 until 50).flatMap { step ->
            (0 until 5).map { width ->
                val min = PhysicsVector(step.toDouble(), (step / 5).toDouble(), width.toDouble())
                PhysicsBox(min, min + PhysicsVector(1.0, 1.0, 1.0))
            }
        }
        val body = world.createBody(PhysicsBodyDefinition(
            transform = PhysicsTransform(PhysicsVector(0.0, 30.0, 0.0)),
            shape = PhysicsShapeDefinition.CompoundBoxes(boxes)
        ))

        assertEquals(250, world.hullVertexCounts(body).size)
        assertEquals(1, world.snapshots().size)
        assertEquals(250f, assertNotNull(world.snapshot(body)).mass, 0.1f)
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(body)).transform.position.y < 30.0)
    }
}
