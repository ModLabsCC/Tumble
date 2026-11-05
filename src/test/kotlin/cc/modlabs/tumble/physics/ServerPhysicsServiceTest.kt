// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.physics.PhysicsBodyDefinition
import cc.modlabs.tumble.physics.PhysicsTransform
import cc.modlabs.tumble.physics.PhysicsVector
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class ServerPhysicsServiceTest {
    @Test
    fun `an invalid rope command cannot stop the physics service`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:invalid-rope")
        service.start()
        try {
            repeat(2) { service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition())) }
            val bodies = mutableListOf<PhysicsBodyId>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { bodies += it.bodyId }
                bodies.size == 2
            }
            service.submit(PhysicsCommand.CreateRope(dimension, bodies[0], bodies[1], 0f))
            service.submit(PhysicsCommand.ApplyCentralImpulse(dimension, bodies[0], PhysicsVector(2.0, 0.0, 0.0)))
            waitUntil { service.latestFrame(dimension).bodies[bodies[0]]?.linearVelocity?.x?.let { it > 0 } == true }
            assertEquals(PhysicsServiceState.RUNNING, service.debugState().state)
        } finally {
            service.close()
        }
    }

    @Test
    fun `submersion submitted after body creation makes a log rise`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:water")
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(
                UUID.randomUUID(), dimension,
                bodyDefinition().copy(
                    transform = PhysicsTransform(PhysicsVector(0.5, 2.0, 0.5)),
                    buoyancy = PhysicsBuoyancy(true, 1.8f, 0.18f, 0.18f, 0.08f)
                )
            ))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            service.submit(PhysicsCommand.SetSubmersion(dimension, body, 1f))
            waitUntil { service.latestFrame(dimension).bodies[body]?.transform?.position?.y?.let { it > 3.0 } == true }
        } finally {
            service.close()
        }
    }

    @Test
    fun `command wakeups cannot advance the simulation ahead of the fixed clock`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:fixed-clock")
        service.start()
        try {
            service.submit(PhysicsCommand.CreateDimension(dimension))
            waitUntil { service.latestFrame(dimension).physicsStep > 0 }
            val first = service.latestFrame(dimension).physicsStep
            val start = System.nanoTime()
            val end = start + 300_000_000L
            while (System.nanoTime() < end) {
                service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(999, 0, 999)))
                Thread.sleep(1)
            }
            val elapsed = System.nanoTime() - start
            val steps = service.latestFrame(dimension).physicsStep - first
            val maxSteps = elapsed / 1_000_000_000.0 * 60 + 10
            assertTrue(steps <= maxSteps, "Advanced $steps steps in ${elapsed / 1_000_000}ms")
        } finally {
            service.close()
        }
    }

    @Test
    fun `body creation acknowledgement keeps its request identity`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val request = UUID.randomUUID()
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), PhysicsDimensionId("test:request"), bodyDefinition(), request))
            var acknowledgement: PhysicsAcknowledgement? = null
            waitUntil { service.pollAcknowledgement()?.let { acknowledgement = it }; acknowledgement != null }
            assertEquals(request, (acknowledgement as PhysicsAcknowledgement.BodyCreated).requestId)
        } finally {
            service.close()
        }
    }

    @Test
    fun `service starts creates a dimension and stops its thread`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        service.start()
        try {
            assertTrue(service.submit(PhysicsCommand.CreateDimension(PhysicsDimensionId("test:level"))))
            waitUntil { service.debugState().activeDimensions == 1 }
            assertEquals(1, service.debugState().activeDimensions)
        } finally {
            service.close()
        }

        assertEquals(PhysicsServiceState.STOPPED, service.debugState().state)
        assertFalse(service.debugState().threadAlive)
    }

    @Test
    fun `startup terrain creates its dimension before restored bodies load`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:startup")
        service.start()
        try {
            service.submit(
                PhysicsCommand.ReplaceTerrainSection(
                    dimension,
                    PhysicsSectionPosition(0, 0, 0),
                    listOf(
                        TerrainBlockGeometry(
                            PhysicsBlockPosition(0, 0, 0),
                            listOf(TerrainBox(PhysicsBox.UNIT, PhysicsMaterial.DEFAULT))
                        )
                    )
                )
            )
            waitUntil { service.debugState().terrainSections == 1 }
            assertEquals(1, service.debugState().activeDimensions)
        } finally {
            service.close()
        }
    }

    @Test
    fun `destroying a body does not remove the same runtime ID from another dimension`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val first = PhysicsDimensionId("test:first")
        val second = PhysicsDimensionId("test:second")
        service.start()
        try {
            service.submit(PhysicsCommand.CreateDimension(first))
            service.submit(PhysicsCommand.CreateDimension(second))
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), first, bodyDefinition()))
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), second, bodyDefinition()))
            waitUntil { service.debugState().runtimeBodies == 2 }
            val acknowledgements = generateSequence(service::pollAcknowledgement).filterIsInstance<PhysicsAcknowledgement.BodyCreated>().toList()
            val firstBody = acknowledgements.single { it.dimension == first }

            service.submit(PhysicsCommand.DestroyBody(first, firstBody.bodyId))
            waitUntil { service.debugState().runtimeBodies == 1 }

            assertEquals(1, service.debugState().runtimeBodies)
        } finally {
            service.close()
        }
    }

    @Test
    fun `central impulse command wakes and moves a body`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:impulse")
        service.start()
        try {
            service.submit(PhysicsCommand.CreateDimension(dimension))
            service.submit(
                PhysicsCommand.SpawnBody(
                    UUID.randomUUID(),
                    dimension,
                    bodyDefinition().copy(buoyancy = PhysicsBuoyancy(enabled = true))
                )
            )
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil {
                val acknowledgement = service.pollAcknowledgement()
                if (acknowledgement is PhysicsAcknowledgement.BodyCreated) created = acknowledgement
                created != null
            }
            val bodyId = requireNotNull(created).bodyId
            service.submit(PhysicsCommand.ApplyCentralImpulse(dimension, bodyId, PhysicsVector(2.0, 0.0, 0.0)))

            waitUntil { service.latestFrame(dimension).bodies[bodyId]?.linearVelocity?.x?.let { it > 0.0 } == true }
            service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodyId, PhysicsVector(-3.0, 0.0, 0.0)))
            waitUntil { service.latestFrame(dimension).bodies[bodyId]?.linearVelocity?.x?.let { it < -2.9 } == true }
            service.submit(PhysicsCommand.AddLinearVelocity(dimension, bodyId, PhysicsVector(5.0, 0.0, 0.0)))
            waitUntil { service.latestFrame(dimension).bodies[bodyId]?.linearVelocity?.x?.let { it > 1.9 } == true }
            service.submit(PhysicsCommand.SetLinearVelocity(dimension, bodyId, PhysicsVector.ZERO))
            service.submit(PhysicsCommand.SetSubmersion(dimension, bodyId, 1f))
            waitUntil { service.latestFrame(dimension).bodies[bodyId]?.linearVelocity?.y?.let { it > 0.0 } == true }
        } finally {
            service.close()
        }
    }

    @Test
    fun `temporary kinematic command expires its body`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:piston")
        service.start()
        try {
            service.submit(PhysicsCommand.CreateDimension(dimension))
            service.submit(
                PhysicsCommand.CreateTemporaryKinematicBody(
                    dimension,
                    bodyDefinition(),
                    PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)),
                    0.1f,
                    30
                )
            )
            waitUntil { service.latestFrame(dimension).bodies.isNotEmpty() }
            waitUntil { service.latestFrame(dimension).bodies.isEmpty() }
        } finally {
            service.close()
        }
    }

    @Test
    fun `impact events cross the physics thread boundary`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:impact")
        service.start()
        try {
            service.submit(PhysicsCommand.CreateDimension(dimension))
            service.submit(
                PhysicsCommand.SpawnBody(
                    UUID.randomUUID(),
                    dimension,
                    bodyDefinition().copy(motionType = PhysicsMotionType.STATIC)
                )
            )
            service.submit(
                PhysicsCommand.SpawnBody(
                    UUID.randomUUID(),
                    dimension,
                    bodyDefinition().copy(
                        transform = PhysicsTransform(PhysicsVector(0.0, 4.0, 0.0)),
                        linearVelocity = PhysicsVector(0.0, -25.0, 0.0),
                        emitImpactEvents = true
                    )
                )
            )

            var impact: PhysicsImpactNotification? = null
            waitUntil {
                impact = impact ?: service.pollImpact()
                impact != null
            }

            assertEquals(dimension, requireNotNull(impact).dimension)
            assertTrue(requireNotNull(impact).event.approachSpeed >= 18f)
        } finally {
            service.close()
        }
    }

    @Test
    fun `runtime world tuning is applied on the physics thread`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:tuning")
        service.start()
        try {
            service.submit(PhysicsCommand.CreateDimension(dimension))
            service.submit(PhysicsCommand.ConfigureWorlds(32.0, 2f, 25f))
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition()))
            var bodyId: PhysicsBodyId? = null
            waitUntil {
                val acknowledgement = service.pollAcknowledgement()
                if (acknowledgement is PhysicsAcknowledgement.BodyCreated) bodyId = acknowledgement.bodyId
                bodyId != null
            }

            waitUntil { service.latestFrame(dimension).bodies[requireNotNull(bodyId)]?.linearVelocity?.y?.let { it > 0.0 } == true }
        } finally {
            service.close()
        }
    }

    private fun bodyDefinition() = PhysicsBodyDefinition(PhysicsTransform(PhysicsVector.ZERO))

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(condition())
    }
}
