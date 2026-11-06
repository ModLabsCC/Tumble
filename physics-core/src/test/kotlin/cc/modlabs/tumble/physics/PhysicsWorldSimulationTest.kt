// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhysicsWorldSimulationTest {
    @Test
    fun `native step bounds extreme linear and angular velocity for dynamic and kinematic bodies`() {
        for (motion in listOf(PhysicsMotionType.DYNAMIC, PhysicsMotionType.KINEMATIC)) {
            PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
                val start = PhysicsVector(8.0, 8.0, 8.0)
                val body = world.createBody(PhysicsBodyDefinition(PhysicsTransform(start), motionType = motion,
                    linearVelocity = PhysicsVector(50_000.0, 0.0, 0.0), angularVelocity = PhysicsVector(0.0, 50_000.0, 0.0)))
                world.step()
                val snapshot = assertNotNull(world.snapshot(body))
                val distance = (snapshot.transform.position - start).length()
                val angle = 2.0 * kotlin.math.acos(kotlin.math.abs(snapshot.transform.rotation.normalized().w).coerceIn(0.0, 1.0))
                assertTrue(distance <= PhysicsWorldConfig.MAX_TERRAIN_STEP_TRANSLATION, "$motion traveled $distance in one step")
                assertTrue(angle <= PhysicsWorldConfig.MAX_TERRAIN_STEP_ROTATION, "$motion rotated $angle in one step")
            }
        }
    }

    @Test
    fun `combined step snapshots match independent reads for moving and static bodies`() = PhysicsWorld().use { world ->
        val moving = world.createBody(bodyAt(0.0, 4.0, 0.0))
        val stationary = world.createBody(bodyAt(4.0, 0.0, 0.0).copy(motionType = PhysicsMotionType.STATIC))
        repeat(5) {
            val combined = world.stepAndSnapshots().associateBy { it.bodyId }
            assertEquals(world.snapshots().associateBy { it.bodyId }, combined)
            assertEquals(setOf(moving, stationary), combined.keys)
        }
        world.destroyBody(moving)
        assertEquals(setOf(stationary), world.stepAndSnapshots().map { it.bodyId }.toSet())
    }

    @Test
    fun `weld preserves relative transform and can be removed`() = PhysicsWorld(
        PhysicsWorldConfig(gravity = PhysicsVector.ZERO)
    ).use { world ->
        val first = world.createBody(bodyAt(0.0, 0.0, 0.0))
        val second = world.createBody(bodyAt(2.0, 0.0, 0.0))

        assertTrue(world.createWeld(first, second))
        assertEquals(1, world.weldCount())
        world.applyCentralImpulse(first, PhysicsVector(10.0, 0.0, 0.0))
        repeat(30) { world.step() }

        val separation = assertNotNull(world.snapshot(second)).transform.position -
            assertNotNull(world.snapshot(first)).transform.position
        assertEquals(2.0, separation.length(), 0.05)
        assertTrue(world.destroyWeld(first, second))
        assertEquals(0, world.weldCount())
    }

    @Test
    fun `tool constraints are native and removable`() = PhysicsWorld(
        PhysicsWorldConfig(gravity = PhysicsVector.ZERO)
    ).use { world ->
        val first = world.createBody(bodyAt(0.0, 0.0, 0.0))
        val second = world.createBody(bodyAt(2.0, 0.0, 0.0))

        assertTrue(world.createRope(first, second, 2f))
        assertTrue(world.createAxis(first, second))
        assertTrue(world.createNoCollide(first, second))
        assertEquals(3, world.destroyToolConstraints(first))
    }

    @Test
    fun `transform updates atomically on the owning thread`() = PhysicsWorld().use { world ->
        val body = world.createBody(bodyAt(0.0, 4.0, 0.0))
        val target = PhysicsTransform(PhysicsVector(3.0, 2.0, -1.0), PhysicsRotation(0.0, 0.707, 0.0, 0.707))

        assertTrue(world.setTransform(body, target))

        val actual = assertNotNull(world.snapshot(body)).transform
        assertEquals(target.position.x, actual.position.x, 1e-5)
        assertEquals(target.position.y, actual.position.y, 1e-5)
        assertEquals(target.position.z, actual.position.z, 1e-5)
        assertEquals(target.rotation.normalized().y, actual.rotation.y, 1e-4)
    }

    @Test
    fun `cube falls onto static terrain and sleeps`() = PhysicsWorld().use { world ->
        world.syncTerrain(listOf(ground()))
        val body = world.createBody(bodyAt(0.0, 4.0, 0.0))

        repeat(360) { world.step() }
        val snapshot = assertNotNull(world.snapshot(body))

        assertTrue(snapshot.transform.position.y in 1.49..1.51, "body y=${snapshot.transform.position.y}")
        assertTrue(snapshot.sleeping)
        assertEquals(true, world.isSleeping(body))
        assertEquals(world.snapshots(), world.stepAndSnapshots())
        assertTrue(world.wake(body))
        assertEquals(false, world.isSleeping(body))
    }

    @Test
    fun `high restitution body bounces`() = PhysicsWorld().use { world ->
        world.syncTerrain(listOf(ground(PhysicsMaterial.HIGH_BOUNCE)))
        val body = world.createBody(bodyAt(0.0, 4.0, 0.0, PhysicsMaterial.HIGH_BOUNCE))
        while (assertNotNull(world.snapshot(body)).transform.position.y > 1.7) world.step()

        var rebound = assertNotNull(world.snapshot(body)).transform.position.y
        repeat(35) {
            world.step()
            rebound = maxOf(rebound, assertNotNull(world.snapshot(body)).transform.position.y)
        }

        assertTrue(rebound > 2.5, "rebound y=$rebound")
    }

    @Test
    fun `low friction slides farther and resistant materials slow movement`() {
        fun distance(material: PhysicsMaterial): Double = PhysicsWorld().use { world ->
            world.syncTerrain(listOf(ground(material)))
            val body = world.createBody(bodyAt(0.0, 1.5, 0.0, material))
            repeat(180) { world.step() }
            world.setLinearVelocity(body, PhysicsVector(8.0, 0.0, 0.0))
            repeat(60) { world.step() }
            assertNotNull(world.snapshot(body)).transform.position.x
        }

        val slippery = distance(PhysicsMaterial.LOW_FRICTION)
        val normal = distance(PhysicsMaterial.DEFAULT)
        val resistant = distance(PhysicsMaterial(friction = 2f, rollingResistance = 1f))

        assertTrue(slippery > normal + 1.0, "slippery=$slippery normal=$normal")
        assertTrue(resistant < normal - 0.5, "resistant=$resistant normal=$normal")
    }

    @Test
    fun `rolling resistance reduces rolling movement`() {
        fun distance(rollingResistance: Float): Double = PhysicsWorld().use { world ->
            val material = PhysicsMaterial(friction = 1f, rollingResistance = rollingResistance)
            world.syncTerrain(listOf(ground(material)))
            val body = world.createBody(bodyAt(0.0, 1.5, 0.0, material))
            repeat(180) { world.step() }
            world.setLinearVelocity(body, PhysicsVector(4.0, 0.0, 0.0))
            world.setAngularVelocity(body, PhysicsVector(0.0, 0.0, -8.0))
            repeat(60) { world.step() }
            assertNotNull(world.snapshot(body)).transform.position.x
        }

        val freeRolling = distance(0f)
        val resisted = distance(1f)

        assertTrue(resisted < freeRolling - 0.25, "resisted=$resisted freeRolling=$freeRolling")
    }

    @Test
    fun `initial velocities and impulses affect snapshots`() = PhysicsWorld(
        PhysicsWorldConfig(gravity = PhysicsVector.ZERO)
    ).use { world ->
        val body = world.createBody(bodyAt(
            0.0, 0.0, 0.0,
            linearVelocity = PhysicsVector(3.0, 0.0, 0.0),
            angularVelocity = PhysicsVector(0.0, 2.0, 0.0)
        ))
        val initialRotation = assertNotNull(world.snapshot(body)).transform.rotation
        repeat(10) { world.step() }
        val moving = assertNotNull(world.snapshot(body))

        assertTrue(moving.transform.position.x > 0.4)
        assertNotEquals(initialRotation, moving.transform.rotation)
        assertTrue(world.applyCentralImpulse(body, PhysicsVector(2.0, 0.0, 0.0)))
        assertTrue(world.applyForceAtPosition(body, PhysicsVector(0.0, 1.0, 0.0), PhysicsVector.ZERO))
        world.step()
        assertTrue(assertNotNull(world.snapshot(body)).linearVelocity.x > moving.linearVelocity.x)
    }

    @Test
    fun `higher density makes a prop harder to push`() = PhysicsWorld(
        PhysicsWorldConfig(gravity = PhysicsVector.ZERO)
    ).use { world ->
        val light = world.createBody(bodyAt(-2.0, 0.0, 0.0, material = PhysicsMaterial(density = 1f)))
        val heavy = world.createBody(bodyAt(2.0, 0.0, 0.0, material = PhysicsMaterial(density = 10f)))
        val impulse = PhysicsVector(2.0, 0.0, 0.0)

        world.applyCentralImpulse(light, impulse)
        world.applyCentralImpulse(heavy, impulse)
        world.step()

        val lightSnapshot = assertNotNull(world.snapshot(light))
        val heavySnapshot = assertNotNull(world.snapshot(heavy))
        val lightSpeed = lightSnapshot.linearVelocity.x
        val heavySpeed = heavySnapshot.linearVelocity.x
        assertEquals(1f, lightSnapshot.mass, 0.02f)
        assertEquals(10f, heavySnapshot.mass, 0.1f)
        assertTrue(lightSpeed > heavySpeed * 5.0, "light=$lightSpeed heavy=$heavySpeed")
    }

    @Test
    fun `static freeze preserves transform and dynamic unfreeze resumes motion`() = PhysicsWorld(
        PhysicsWorldConfig(gravity = PhysicsVector.ZERO)
    ).use { world ->
        val body = world.createBody(bodyAt(0.0, 0.0, 0.0, linearVelocity = PhysicsVector(2.0, 0.0, 0.0)))
        val mass = assertNotNull(world.snapshot(body)).mass
        assertTrue(world.setMotionType(body, PhysicsMotionType.STATIC))
        val frozenSnapshot = assertNotNull(world.snapshot(body))
        val frozen = frozenSnapshot.transform
        assertEquals(mass, frozenSnapshot.mass)
        repeat(10) { world.step() }
        assertEquals(frozen, assertNotNull(world.snapshot(body)).transform)

        assertTrue(world.setMotionType(body, PhysicsMotionType.DYNAMIC))
        assertTrue(world.setLinearVelocity(body, PhysicsVector(2.0, 0.0, 0.0)))
        world.step()
        assertTrue(assertNotNull(world.snapshot(body)).transform.position.x > frozen.position.x)
    }

    @Test
    fun `box and compound shapes keep exact dynamic hulls`() = PhysicsWorld().use { world ->
        val box = world.createBody(bodyAt(0.0, 0.0, 0.0))
        val compound = world.createBody(bodyAt(
            3.0,
            0.0,
            0.0,
            shape = PhysicsShapeDefinition.CompoundBoxes(listOf(
                PhysicsBox.centered(1.0, 1.0, 1.0),
                PhysicsBox(PhysicsVector(0.5, -0.25, -0.25), PhysicsVector(1.5, 0.25, 0.25))
            ))
        ))

        assertEquals(1, world.hullVertexCounts(box).size)
        assertEquals(8, world.hullVertexCounts(box).single())
        assertEquals(2, world.hullVertexCounts(compound).size)
        assertTrue(world.hullVertexCounts(compound).all { it == 8 })
    }

    @Test
    fun `invalid boxes and configuration are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PhysicsBox(PhysicsVector.ZERO, PhysicsVector(0.0, 1.0, 1.0))
        }
        assertFailsWith<IllegalArgumentException> { PhysicsWorldConfig(frequencyHz = 0) }
        assertFailsWith<IllegalArgumentException> { PhysicsWorldConfig(substeps = 0) }
        assertFailsWith<IllegalArgumentException> { PhysicsMaterial(density = 0f) }
    }
}
