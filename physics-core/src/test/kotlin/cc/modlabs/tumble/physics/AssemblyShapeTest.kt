// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssemblyShapeTest {
    @Test
    fun `removing a weld releases the unblocked body`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)))
        assertTrue(world.createWeld(first, second))
        assertTrue(world.suspend(first))
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.destroyWeld(first, second))
        assertTrue(assertNotNull(world.snapshot(first)).suspended)
        assertFalse(assertNotNull(world.snapshot(second)).suspended)
        assertFalse(world.createWeld(first, second))
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(second)).transform.position.x > 2.2)
    }

    @Test
    fun `connected bodies stay paused until all terrain blockers clear`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(3.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)))
        assertTrue(world.createRope(first, second, 3f))
        assertTrue(world.suspend(first))
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.suspend(second))
        val start = assertNotNull(world.snapshot(second))
        assertTrue(world.resume(first))
        repeat(30) { world.step() }
        val paused = assertNotNull(world.snapshot(second))
        assertTrue(assertNotNull(world.snapshot(first)).suspended)
        assertTrue(paused.suspended)
        assertEquals(start.transform.position.x, paused.transform.position.x, 0.001)
        assertTrue(world.resume(second))
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(second)).transform.position.x > paused.transform.position.x + 0.2)
    }

    @Test
    fun `suspending and resuming a moving body preserves its motion`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val body = world.createBody(PhysicsBodyDefinition(
            PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)),
            linearVelocity = PhysicsVector(2.0, 0.0, 0.0), angularVelocity = PhysicsVector(0.0, 1.0, 0.0)
        ))
        repeat(10) { world.step() }
        val before = assertNotNull(world.snapshot(body))
        assertTrue(world.suspend(body))
        repeat(60) { world.step() }
        val paused = assertNotNull(world.snapshot(body))
        assertEquals(before.transform.position.x, paused.transform.position.x, 0.001)
        assertEquals(before.linearVelocity.x, paused.linearVelocity.x, 0.001)
        assertEquals(before.angularVelocity.y, paused.angularVelocity.y, 0.001)
        assertFalse(world.setLinearVelocity(body, PhysicsVector(100.0, 0.0, 0.0)))
        assertFalse(world.applyCentralImpulse(body, PhysicsVector(100.0, 0.0, 0.0)))
        assertTrue(world.resume(body))
        repeat(10) { world.step() }
        val resumed = assertNotNull(world.snapshot(body))
        assertTrue(resumed.transform.position.x > paused.transform.position.x + 0.2)
        assertEquals(2.0, resumed.linearVelocity.x, 0.02)
        assertEquals(1.0, resumed.angularVelocity.y, 0.02)
    }

    @Test
    fun `assembly mass uses each block material`() = PhysicsWorld().use { world ->
        val first = PhysicsBox(PhysicsVector(0.0, 0.0, 0.0), PhysicsVector(1.0, 1.0, 1.0))
        val second = PhysicsBox(PhysicsVector(1.0, 0.0, 0.0), PhysicsVector(2.0, 1.0, 1.0))
        val body = world.createBody(PhysicsBodyDefinition(
            transform = PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)),
            shape = PhysicsShapeDefinition.MaterialBoxes(listOf(
                PhysicsMaterialBox(first, PhysicsMaterial(density = 0.5f)),
                PhysicsMaterialBox(second, PhysicsMaterial(density = 8f))
            ))
        ))
        assertEquals(8.5f, assertNotNull(world.snapshot(body)).mass, 0.02f)
        assertEquals(2, world.hullVertexCounts(body).size)
        assertEquals(1, world.snapshots().size)
    }

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
