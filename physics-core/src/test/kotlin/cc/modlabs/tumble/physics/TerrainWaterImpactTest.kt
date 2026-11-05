// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainWaterImpactTest {
    @Test
    fun `failed terrain rebuild retains the previous collider and geometry`() = PhysicsWorld().use { world ->
        val position = PhysicsBlockPosition(0, 0, 0)
        val original = TerrainBlockGeometry(position, listOf(TerrainBox(PhysicsBox.UNIT)))
        world.syncTerrain(listOf(original))
        val invalid = (0 until 257).map { i ->
            val x = (i % 8) * 0.125
            val y = (i / 64) * 0.125
            val z = (i / 8 % 8) * 0.125
            TerrainBox(PhysicsBox(PhysicsVector(x, y, z), PhysicsVector(x + 0.05, y + 0.05, z + 0.05)),
                PhysicsMaterial(friction = i.toFloat() / 100f))
        }
        assertFailsWith<IllegalArgumentException> { world.syncTerrain(listOf(TerrainBlockGeometry(position, invalid))) }
        assertEquals(1, world.terrainSectionCount())
        assertTrue(world.syncTerrain(listOf(original)).isEmpty())
    }

    @Test
    fun `a sleeping buoyant body wakes when water disappears`() {
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
            val body = world.createBody(bodyAt(0.0, 0.0, 0.0, buoyancy = PhysicsBuoyancy(enabled = true)))
            world.setSubmersion(body, 1f)
            repeat(360) { world.step() }
            assertEquals(true, world.isSleeping(body))
            world.setSubmersion(body, 0f)
            assertEquals(false, world.isSleeping(body))
        }
    }

    @Test
    fun `floating body levels its nearest upper face`() {
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector(0.0, -10.0, 0.0))).use { world ->
            val angle = PI / 6.0
            val body = world.createBody(
                PhysicsBodyDefinition(
                    transform = PhysicsTransform(
                        PhysicsVector.ZERO,
                        PhysicsRotation(0.0, 0.0, sin(angle / 2.0), cos(angle / 2.0))
                    ),
                    buoyancy = PhysicsBuoyancy(true, 1.8f, 0.18f, 0.18f, 0.08f)
                )
            )
            world.setSubmersion(body, 1f)
            repeat(360) { world.step() }

            val settled = assertNotNull(world.snapshot(body))
            val rotation = settled.transform.rotation.normalized()
            val localUpY = 1.0 - 2.0 * (rotation.x * rotation.x + rotation.z * rotation.z)
            assertTrue(localUpY > 0.995, "upper face did not level: y=$localUpY")
            assertTrue(settled.angularVelocity.length() < 0.1, "body is still rotating: ${settled.angularVelocity}")
        }
    }

    @Test
    fun `buoyant body settles instead of repeatedly launching out of water`() {
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector(0.0, -10.0, 0.0))).use { world ->
            val body = world.createBody(bodyAt(
                0.0, -0.5, 0.0,
                buoyancy = PhysicsBuoyancy(true, 2f, 0.2f, 0.2f)
            ))
            var highest = -0.5
            repeat(300) {
                val center = assertNotNull(world.snapshot(body)).transform.position.y
                val contact = (minOf(center + 0.5, 0.0) - maxOf(center - 0.5, -1.0)).coerceIn(0.0, 1.0)
                world.setSubmersion(body, contact.toFloat())
                world.step()
                highest = maxOf(highest, assertNotNull(world.snapshot(body)).transform.position.y)
            }

            val settled = assertNotNull(world.snapshot(body))
            assertTrue(highest < 0.6, "body floated too high: y=$highest")
            assertTrue(settled.transform.position.y in -0.35..0.0, "resting y=${settled.transform.position.y}")
            assertTrue(kotlin.math.abs(settled.linearVelocity.y) < 0.05, "vertical velocity=${settled.linearVelocity.y}")
        }
    }

    @Test
    fun `heavy submerged body keeps sinking instead of sleeping in place`() {
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector(0.0, -32.0, 0.0))).use { world ->
            val body = world.createBody(bodyAt(
                0.0, 0.0, 0.0,
                buoyancy = PhysicsBuoyancy(true, 0.45f, 0.12f, 0.16f)
            ))
            world.setBuoyancyForceScale(1.5f)
            world.setSubmersion(body, 1f)
            repeat(300) { world.step() }

            val sunk = assertNotNull(world.snapshot(body))
            assertTrue(sunk.transform.position.y < -1.0, "body stopped sinking at y=${sunk.transform.position.y}")
            assertFalse(sunk.sleeping)
        }
    }

    @Test
    fun `terrain groups by section removes internal faces and rebuilds locally`() = PhysicsWorld().use { world ->
        val first = TerrainBlockGeometry(
            PhysicsBlockPosition(0, 0, 0),
            listOf(TerrainBox(PhysicsBox(PhysicsVector(0.0, 0.0, 0.0), PhysicsVector(1.0, 1.0, 1.0))))
        )
        val adjacent = TerrainBlockGeometry(
            PhysicsBlockPosition(1, 0, 0),
            listOf(TerrainBox(PhysicsBox(PhysicsVector(1.0, 0.0, 0.0), PhysicsVector(2.0, 1.0, 1.0))))
        )
        val distant = TerrainBlockGeometry(
            PhysicsBlockPosition(16, 0, 0),
            listOf(TerrainBox(
                PhysicsBox(PhysicsVector(16.0, 0.0, 0.0), PhysicsVector(17.0, 1.0, 1.0)),
                PhysicsMaterial.LOW_FRICTION
            ))
        )

        assertEquals(setOf(PhysicsSectionPosition(0, 0, 0)), world.syncTerrain(listOf(first, adjacent)))
        assertEquals(1, world.terrainSectionCount())
        assertEquals(20, world.terrainTriangleCount(PhysicsSectionPosition(0, 0, 0)))
        assertEquals(setOf(PhysicsSectionPosition(1, 0, 0)), world.syncTerrain(listOf(distant)))
        assertEquals(2, world.terrainSectionCount())
    }

    @Test
    fun `terrain replacement and removal release stale sections including negatives`() = PhysicsWorld().use { world ->
        val negative = TerrainBlockGeometry(
            PhysicsBlockPosition(-17, 0, 0),
            listOf(TerrainBox(PhysicsBox(PhysicsVector(-17.0, 0.0, 0.0), PhysicsVector(-16.0, 1.0, 1.0))))
        )
        world.syncTerrain(listOf(negative, ground()))
        assertEquals(2, world.terrainSectionCount())

        world.replaceTerrain(listOf(negative))
        assertEquals(1, world.terrainSectionCount())
        assertTrue(world.removeTerrainSection(PhysicsSectionPosition(-2, 0, 0)))
        assertFalse(world.removeTerrainSection(PhysicsSectionPosition(-2, 0, 0)))
        assertEquals(0, world.terrainSectionCount())
    }

    @Test
    fun `buoyancy applies upward force and damping only when enabled`() {
        val response = PhysicsBuoyancy(enabled = true, forceScale = 2f, linearDampingPerStep = 0.25f, angularDampingPerStep = 0.5f)
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector(0.0, -10.0, 0.0))).use { world ->
            val buoyant = world.createBody(bodyAt(
                0.0, 0.0, 0.0,
                linearVelocity = PhysicsVector(4.0, 0.0, 0.0),
                angularVelocity = PhysicsVector(0.0, 2.0, 0.0),
                buoyancy = response
            ))
            val weaker = world.createBody(bodyAt(
                2.0, 0.0, 0.0,
                buoyancy = response.copy(forceScale = 1f)
            ))
            val plain = world.createBody(bodyAt(4.0, 0.0, 0.0))
            val waterCapableButDry = world.createBody(bodyAt(6.0, 0.0, 0.0, buoyancy = response))
            world.setSubmersion(buoyant, 1f)
            world.setSubmersion(weaker, 1f)
            world.setSubmersion(plain, 1f)
            world.setBuoyancyForceScale(0.5f)
            world.step()

            val wet = assertNotNull(world.snapshot(buoyant))
            val lessBuoyant = assertNotNull(world.snapshot(weaker))
            val dry = assertNotNull(world.snapshot(plain))
            val dryWaterCapable = assertNotNull(world.snapshot(waterCapableButDry))
            assertTrue(wet.linearVelocity.y > dry.linearVelocity.y)
            assertTrue(wet.linearVelocity.y > lessBuoyant.linearVelocity.y)
            assertTrue(lessBuoyant.linearVelocity.y < 0.0)
            assertTrue(wet.linearVelocity.x < 4.0)
            assertTrue(wet.angularVelocity.y < 2.0)
            assertEquals(0.0, dry.linearVelocity.x, 0.0001)
            assertEquals(dry.linearVelocity.y, dryWaterCapable.linearVelocity.y, 0.0001)
            assertFailsWith<IllegalArgumentException> { world.setSubmersion(buoyant, 1.1f) }
        }
    }

    @Test
    fun `significant impacts use stable body IDs while low speed contacts are filtered`() {
        PhysicsWorld(PhysicsWorldConfig(impactSpeedThreshold = 5f)).use { world ->
            world.syncTerrain(listOf(ground()))
            val body = world.createBody(bodyAt(0.0, 10.0, 0.0, emitImpacts = true))
            repeat(180) { world.step() }

            val impacts = world.consumeImpactEvents()
            assertTrue(impacts.any { it.bodyA == body || it.bodyB == body })
            assertTrue(impacts.all { it.approachSpeed >= 5f })
            assertTrue(world.consumeImpactEvents().isEmpty())
        }

        PhysicsWorld(PhysicsWorldConfig(impactSpeedThreshold = 100f)).use { world ->
            world.syncTerrain(listOf(ground()))
            world.createBody(bodyAt(0.0, 2.0, 0.0, emitImpacts = true))
            repeat(120) { world.step() }
            assertTrue(world.consumeImpactEvents().isEmpty())
        }
    }

    @Test
    fun `temporary kinematic bodies move and expire deterministically`() = PhysicsWorld(
        PhysicsWorldConfig(gravity = PhysicsVector.ZERO)
    ).use { world ->
        val body = world.createTemporaryKinematicBody(
            bodyAt(0.0, 0.0, 0.0),
            PhysicsTransform(PhysicsVector(6.0, 0.0, 0.0)),
            travelSeconds = 0.1f,
            lifetimeSteps = 8
        )

        repeat(3) { world.step() }
        assertTrue(world.containsBody(body))
        assertTrue(assertNotNull(world.snapshot(body)).transform.position.x > 0.0)
        repeat(5) { world.step() }
        assertFalse(world.containsBody(body))
        assertNull(world.snapshot(body))
        assertTrue(world.movedSnapshots().none { it.bodyId == body })
    }
}
