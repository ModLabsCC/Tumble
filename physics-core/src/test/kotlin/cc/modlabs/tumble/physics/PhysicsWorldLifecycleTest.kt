// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysicsWorldLifecycleTest {
    @Test
    fun `world creates steps and closes with active bodies`() {
        val world = PhysicsWorld()
        val body = world.createBody(bodyAt(0.0, 4.0, 0.0))

        assertTrue(world.containsBody(body))
        world.step()
        assertNotNull(world.snapshot(body))
        world.close()
        world.close()
        assertFailsWith<IllegalStateException> { world.step() }
    }

    @Test
    fun `destroy and unknown body operations are predictable`() = PhysicsWorld().use { world ->
        val first = world.createBody(bodyAt(0.0, 0.0, 0.0))
        assertTrue(world.destroyBody(first))
        assertFalse(world.destroyBody(first))
        assertNull(world.snapshot(first))
        assertFalse(world.setLinearVelocity(first, PhysicsVector.ZERO))
        assertFalse(world.destroyBody(PhysicsBodyId(999_999)))
    }

    @Test
    fun `body IDs are stable and never reuse native identity`() = PhysicsWorld().use { world ->
        val first = world.createBody(bodyAt(0.0, 0.0, 0.0))
        world.destroyBody(first)
        val second = world.createBody(bodyAt(0.0, 0.0, 0.0))

        assertNotEquals(first, second)
        assertTrue(second.value > first.value)
    }

    @Test
    fun `snapshots are immutable values`() = PhysicsWorld().use { world ->
        val body = world.createBody(bodyAt(0.0, 4.0, 0.0))
        val before = assertNotNull(world.snapshot(body))

        repeat(10) { world.step() }
        val after = assertNotNull(world.snapshot(body))

        assertNotEquals(before.transform, after.transform)
        assertEquals(PhysicsVector(0.0, 4.0, 0.0), before.transform.position)
        assertEquals(body, before.bodyId)
        world.destroyBody(body)
        assertNull(world.snapshot(body))
        assertTrue(world.snapshots().isEmpty())
    }
}

internal fun bodyAt(
    x: Double,
    y: Double,
    z: Double,
    material: PhysicsMaterial = PhysicsMaterial.DEFAULT,
    linearVelocity: PhysicsVector = PhysicsVector.ZERO,
    angularVelocity: PhysicsVector = PhysicsVector.ZERO,
    shape: PhysicsShapeDefinition = PhysicsShapeDefinition.Box(PhysicsBox.UNIT),
    buoyancy: PhysicsBuoyancy = PhysicsBuoyancy(),
    emitImpacts: Boolean = false
) = PhysicsBodyDefinition(
    PhysicsTransform(PhysicsVector(x, y, z)),
    shape,
    material,
    linearVelocity,
    angularVelocity,
    buoyancy = buoyancy,
    emitImpactEvents = emitImpacts
)

internal fun ground(material: PhysicsMaterial = PhysicsMaterial.DEFAULT) = TerrainBlockGeometry(
    PhysicsBlockPosition(0, 0, 0),
    listOf(TerrainBox(
        PhysicsBox(PhysicsVector(-50.0, 0.0, -5.0), PhysicsVector(50.0, 1.0, 5.0)),
        material
    ))
)
