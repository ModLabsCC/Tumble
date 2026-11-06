// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssemblyShapeTest {
    @Test
    fun `restored weld retains its original frames when saved bodies diverge`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        val frames = assertNotNull(world.captureJointFrames(first, second))
        assertTrue(world.setTransform(second, PhysicsTransform(PhysicsVector(4.0, 10.0, 0.0))))
        assertTrue(world.createWeld(first, second, frames))
        assertTrue(world.setMotionType(first, PhysicsMotionType.DYNAMIC))
        assertTrue(world.setMotionType(second, PhysicsMotionType.DYNAMIC))
        repeat(180) { world.step() }
        val separation = assertNotNull(world.snapshot(second)).transform.position - assertNotNull(world.snapshot(first)).transform.position
        assertEquals(2.0, separation.length(), 0.1)
    }

    @Test
    fun `restored axis retains its original anchor when saved bodies diverge`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        val frames = assertNotNull(world.captureJointFrames(first, second))
        assertTrue(world.setTransform(second, PhysicsTransform(PhysicsVector(4.0, 10.0, 0.0))))
        assertTrue(world.createAxis(first, second, frames))
        assertTrue(world.setMotionType(first, PhysicsMotionType.DYNAMIC))
        assertTrue(world.setMotionType(second, PhysicsMotionType.DYNAMIC))
        repeat(180) { world.step() }
        val separation = assertNotNull(world.snapshot(second)).transform.position - assertNotNull(world.snapshot(first)).transform.position
        assertEquals(2.0, separation.length(), 0.1)
    }

    @Test
    fun `tool joints can be restored while both bodies are parked`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        assertTrue(world.createRope(first, second, 2f))
        assertTrue(world.createAxis(first, second))
        assertTrue(world.createNoCollide(first, second))
        assertTrue(world.setMotionType(first, PhysicsMotionType.DYNAMIC))
        assertTrue(world.setMotionType(second, PhysicsMotionType.DYNAMIC))
        assertTrue(world.applyCentralImpulse(first, PhysicsVector(4.0, 0.0, 0.0)))
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(second)).transform.position.x > 2.1)
        assertEquals(3, world.destroyToolConstraints(first))
    }

    @Test
    fun `staged component stays static until explicit activation`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
        val definitions = listOf(
            PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)),
            PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0))
        )
        val ids = assertNotNull(world.restoreComponent(definitions, listOf(RestoredJoint(0, 1, PhysicsJointKind.WELD, frames)), false))
        repeat(30) { world.step() }
        assertEquals(0.0, assertNotNull(world.snapshot(ids[0])).transform.position.x, 0.001)
        assertTrue(world.activateRestoredComponent(ids, definitions))
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(ids[0])).transform.position.x > 0.1)
    }

    @Test
    fun `restoring a component rolls back all bodies if any joint fails`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
        val definitions = listOf(
            PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))),
            PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)))
        )
        val weld = RestoredJoint(0, 1, PhysicsJointKind.WELD, frames)
        assertEquals(null, world.restoreComponent(definitions, listOf(weld, weld)))
        assertTrue(world.snapshots().isEmpty(), "failed restore left partial bodies")
        assertEquals(0, world.weldCount())
    }

    @Test
    fun `restoring a component activates linked bodies only after all joints exist`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
        val definitions = listOf(
            PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)),
            PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(4.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0))
        )
        val ids = assertNotNull(world.restoreComponent(definitions, listOf(RestoredJoint(0, 1, PhysicsJointKind.WELD, frames))))
        repeat(180) { world.step() }
        val first = assertNotNull(world.snapshot(ids[0]))
        val second = assertNotNull(world.snapshot(ids[1]))
        assertEquals(2.0, (second.transform.position - first.transform.position).length(), 0.1)
        assertTrue(first.transform.position.x > 1.0, "saved velocity was not restored")
    }

    @Test
    fun `connection preparation cannot release a concurrent terrain suspension`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0))))
        val request = java.util.UUID.randomUUID()
        assertNotNull(world.prepareConnection(request, first, second))
        assertTrue(assertNotNull(world.snapshot(first)).suspended)
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.suspend(first))
        assertTrue(world.cancelConnectionPreparation(request))
        assertTrue(assertNotNull(world.snapshot(first)).suspended)
        assertFalse(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.resume(first))
        assertFalse(assertNotNull(world.snapshot(first)).suspended)
    }

    @Test
    fun `destroying a prepared endpoint keeps its peer parked until cancellation`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0))))
        val request = java.util.UUID.randomUUID()
        assertNotNull(world.prepareConnection(request, first, second))
        assertTrue(world.destroyBody(first))
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.cancelConnectionPreparation(request))
        assertFalse(assertNotNull(world.snapshot(second)).suspended)
    }

    @Test
    fun `installing a prepared weld preserves terrain suspension across the new joint`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0))))
        val request = java.util.UUID.randomUUID()
        val frames = assertNotNull(world.prepareConnection(request, first, second))
        assertTrue(world.suspend(first))
        assertTrue(world.installPreparedConnection(request, first, second, PhysicsJointKind.WELD, frames, 0f))
        assertTrue(assertNotNull(world.snapshot(first)).suspended)
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.resume(first))
        assertFalse(assertNotNull(world.snapshot(first)).suspended)
        assertFalse(assertNotNull(world.snapshot(second)).suspended)
    }

    @Test
    fun `failed prepared connection remains parked until explicit cancellation`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0))))
        val request = java.util.UUID.randomUUID()
        val frames = assertNotNull(world.prepareConnection(request, first, second))
        assertFalse(world.installPreparedConnection(request, first, second, PhysicsJointKind.ROPE, frames, 0f))
        assertTrue(assertNotNull(world.snapshot(first)).suspended)
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(world.cancelConnectionPreparation(request))
    }

    @Test
    fun `a weld can be restored while both bodies are parked`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), motionType = PhysicsMotionType.STATIC))
        assertTrue(world.createWeld(first, second))
        assertEquals(1, world.weldCount())
        assertTrue(world.setMotionType(first, PhysicsMotionType.DYNAMIC))
        assertTrue(world.setMotionType(second, PhysicsMotionType.DYNAMIC))
        assertTrue(world.setLinearVelocity(first, PhysicsVector(2.0, 0.0, 0.0)))
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(second)).transform.position.x > 2.1)
    }

    @Test
    fun `unloading a suspended root does not release its connected body`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val root = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val other = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)))
        assertTrue(world.createWeld(root, other))
        assertTrue(world.suspend(root))
        assertTrue(world.destroyBody(root))
        assertTrue(assertNotNull(world.snapshot(other)).suspended)
        repeat(30) { world.step() }
        assertEquals(2.0, assertNotNull(world.snapshot(other)).transform.position.x, 0.001)
        assertTrue(world.resume(root))
        repeat(30) { world.step() }
        assertTrue(assertNotNull(world.snapshot(other)).transform.position.x > 2.2)
    }

    @Test
    fun `removing a weld releases the unblocked body`() = PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
        val first = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(0.0, 10.0, 0.0))))
        val second = world.createBody(PhysicsBodyDefinition(PhysicsTransform(PhysicsVector(2.0, 10.0, 0.0)), linearVelocity = PhysicsVector(2.0, 0.0, 0.0)))
        assertTrue(world.createWeld(first, second))
        assertTrue(world.suspend(first))
        assertTrue(assertNotNull(world.snapshot(second)).suspended)
        assertTrue(first in assertNotNull(world.snapshot(second)).suspensionRoots)
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
