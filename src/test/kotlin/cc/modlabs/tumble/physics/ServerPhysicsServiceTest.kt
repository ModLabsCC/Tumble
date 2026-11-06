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
    fun `consecutive invalidations coalesce without crossing replacements or spawn barriers`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:invalidation-order")
        val section = PhysicsSectionPosition(0, 0, 0)
        service.start()
        try {
            val lock = service.javaClass.getDeclaredField("stepBoundary").apply { isAccessible = true }.get(service) as java.util.concurrent.locks.ReentrantLock
            lock.lock()
            try {
                repeat(2000) { assertTrue(service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, section))) }
                service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, section, emptyList()))
                repeat(2000) { assertTrue(service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, section))) }
                service.submitSuspendedSpawn(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition()))
                val queue = service.javaClass.getDeclaredField("commands").apply { isAccessible = true }.get(service) as java.util.concurrent.ConcurrentLinkedQueue<*>
                val ordered = queue.map { item -> requireNotNull(item).javaClass.getDeclaredField("command").apply { isAccessible = true }.get(item) }
                assertEquals(5, ordered.size, "duplicate invalidations were not coalesced")
                assertTrue(ordered[0] is PhysicsCommand.InvalidateTerrainSection && ordered[1] is PhysicsCommand.ReplaceTerrainSection &&
                    ordered[2] is PhysicsCommand.InvalidateTerrainSection && ordered[3] is PhysicsCommand.SpawnBody &&
                    ordered[4] is PhysicsCommand.SuspendProp, "terrain or spawn/suspend FIFO ordering changed")
            } finally { lock.unlock() }
            waitUntil { service.debugState().queuedCommands == 0 }
        } finally { service.close() }
    }

    @Test
    fun `barrier admission bounds queued invalidations and releases producers`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:bounded-invalidations")
        val maxQueued = java.util.concurrent.atomic.AtomicInteger()
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        service.start()
        try {
            val lock = service.javaClass.getDeclaredField("stepBoundary").apply { isAccessible = true }.get(service) as java.util.concurrent.locks.ReentrantLock
            val producer = Thread {
                lock.lock()
                try {
                    repeat(3000) { index ->
                        check(service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, PhysicsSectionPosition(index, 0, 0))))
                        maxQueued.accumulateAndGet(service.debugState().queuedCommands, ::maxOf)
                    }
                    completed.set(true)
                } finally { lock.unlock() }
            }.apply { isDaemon = true; start() }
            producer.join(10_000)
            assertTrue(completed.get() && !producer.isAlive, "barrier producer did not resume after backpressure")
            assertTrue(maxQueued.get() <= 1024, "barrier queue exceeded its bound: ${maxQueued.get()}")
            waitUntil { service.debugState().queuedCommands == 0 }
        } finally { service.close() }
    }

    @Test
    fun `normal backlog leaves barrier capacity and concurrent shutdown releases producers`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:shutdown-backlog")
        val maxQueued = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        service.start()
        val lock = service.javaClass.getDeclaredField("stepBoundary").apply { isAccessible = true }.get(service) as java.util.concurrent.locks.ReentrantLock
        val producer = Thread {
            try {
                lock.lock()
                try {
                    repeat(960) { index ->
                        check(service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, PhysicsSectionPosition(index, 0, 0), emptyList())))
                    }
                    started.countDown()
                    // This waits for ordinary capacity and releases the reentrant lock.
                    repeat(5000) { index ->
                        if (!service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, PhysicsSectionPosition(index, 1, 0), emptyList()))) return@Thread
                        maxQueued.accumulateAndGet(service.debugState().queuedCommands, ::maxOf)
                    }
                } finally { lock.unlock() }
            } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS), "backlog was not established")
            assertTrue(service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, PhysicsSectionPosition(-1, 0, 0))), "reserved barrier could not be admitted")
            val closer = Thread { service.close() }.apply { isDaemon = true; start() }
            closer.join(10_000)
            assertFalse(closer.isAlive, "shutdown stalled behind a command backlog")
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "blocked producer did not exit after shutdown")
            assertTrue(maxQueued.get() <= 1024, "total queue exceeded limit: ${maxQueued.get()}")
            assertFalse(service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, PhysicsSectionPosition(-2, 0, 0))), "submission accepted after shutdown")
        } finally { service.close() }
    }

    @Test
    fun `four consecutive interrupted scans require fail closed parking`() {
        val restarts = TerrainScanRestarts(4)
        repeat(3) { restarts.invalidated(); assertFalse(restarts.shouldPark()) }
        restarts.invalidated()
        assertTrue(restarts.shouldPark())
        restarts.reset()
        assertFalse(restarts.shouldPark())
        repeat(3) { restarts.invalidated() }
        restarts.reset() // A completed scan breaks the consecutive sequence.
        restarts.invalidated()
        assertFalse(restarts.shouldPark())
    }

    @Test
    fun `nine connected assemblies larger than a terrain scan budget can advance`() = checkConnectedTerrainBudget(9, false)

    @Test
    fun `terrain scan restarts park a large component without starving unrelated physics`() = checkConnectedTerrainBudget(400, true)

    private fun checkConnectedTerrainBudget(count: Int, stress: Boolean) {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:connected-terrain-budget")
        val box = PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))
        val guard = AssemblyTerrainGuard(List(110) { box }, 0, 2)
        val definitions = (0 until count).map { index ->
            bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0 + 16.0 * index, 8.0, 8.0)))
        }
        val coverage = definitions.map { requireNotNull(AssemblyTerrainCoverage.calculate(guard, it.transform, PhysicsVector.ZERO, PhysicsVector.ZERO)) }
        assertTrue(coverage.sumOf { it.visits } > 16_384, "fixture does not exceed the global scan budget")
        val bodies = definitions.map { RestoreBody(UUID.randomUUID(), UUID.randomUUID(), it, guard) }
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(8.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-8.0, 0.0, 0.0)))
        val joints = (0 until count - 1).map { RestoredJoint(it, it + 1, PhysicsJointKind.WELD, frames) }
        val token = PhysicsRestoreToken()
        service.start()
        try {
            service.submit(PhysicsCommand.ConfigureWorlds(0.0, 1f, 18f))
            coverage.flatMap { it.sections }.toSet().forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            service.submit(PhysicsCommand.RestoreComponent(dimension, bodies, joints, token))
            var staged: PhysicsAcknowledgement.ComponentStaged? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentStaged)?.let { staged = it }; staged != null }
            assertTrue(requireNotNull(staged).success, "component did not stage")
            service.submit(PhysicsCommand.ActivateRestoredComponent(token))
            var activated: PhysicsAcknowledgement.ComponentActivated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentActivated)?.let { activated = it }; activated != null }
            assertTrue(requireNotNull(activated).success, "component did not activate")
            val ids = requireNotNull(staged).bodies.map { it.bodyId }
            val initial = service.latestFrame(dimension).physicsStep
            waitUntil {
                val frame = service.latestFrame(dimension)
                frame.physicsStep >= initial + 10 && ids.all { frame.bodies[it]?.suspended == false }
            }
            if (!stress) return
            val ordinaryDimension = PhysicsDimensionId("test:ordinary-progress")
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), ordinaryDimension, bodyDefinition()))
            var ordinary: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { ordinary = it }; ordinary != null }
            val ordinaryBody = requireNotNull(ordinary).bodyId
            fun whileCommands(command: () -> Unit, requireGuardedActive: Boolean, delayNanos: Long = 1_000_000L, extra: () -> Boolean = { true }) {
                val active = java.util.concurrent.atomic.AtomicBoolean(true)
                val producer = Thread {
                    while (active.get()) {
                        command()
                        java.util.concurrent.locks.LockSupport.parkNanos(delayNanos)
                    }
                }.apply { isDaemon = true; start() }
                try {
                    val start = service.latestFrame(ordinaryDimension).physicsStep
                    try {
                        waitUntil { service.latestFrame(ordinaryDimension).physicsStep >= start + 10 && extra() }
                    } catch (error: AssertionError) {
                        val invalidations = service.javaClass.getDeclaredField("terrainScanInvalidations").apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
                        val fallbacks = service.javaClass.getDeclaredField("terrainScanFallbacks").apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
                        throw AssertionError("ordinary start=$start now=${service.latestFrame(ordinaryDimension).physicsStep} invalidations=${invalidations.get()} fallbacks=${fallbacks.get()} service=${service.debugState()}", error)
                    }
                    assertTrue(producer.isAlive, "producer stopped before ordinary physics advanced")
                    if (requireGuardedActive) assertTrue(ids.all { service.latestFrame(dimension).bodies[it]?.suspended == false },
                        "unrelated ordinary commands parked a guarded component")
                } finally {
                    active.set(false)
                    producer.join(2000)
                }
            }
            whileCommands({ service.submit(PhysicsCommand.SetLinearVelocity(ordinaryDimension, ordinaryBody, PhysicsVector(1.0, 0.0, 0.0))) }, true)
            val unrelated = PhysicsSectionPosition(1000, 0, 0)
            val invalidations = service.javaClass.getDeclaredField("terrainScanInvalidations").apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
            val fallbacks = service.javaClass.getDeclaredField("terrainScanFallbacks").apply { isAccessible = true }.get(service) as java.util.concurrent.atomic.AtomicLong
            val beforeInvalidations = invalidations.get()
            val beforeFallbacks = fallbacks.get()
            val guardedStart = service.latestFrame(dimension).physicsStep
            val alternatingMaxQueued = java.util.concurrent.atomic.AtomicInteger()
            whileCommands({
                service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, unrelated))
                service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, unrelated, emptyList()))
                alternatingMaxQueued.accumulateAndGet(service.debugState().queuedCommands, ::maxOf)
            }, false, 0L, {
                val frame = service.latestFrame(dimension)
                (fallbacks.get() > beforeFallbacks && ids.all { frame.bodies[it]?.suspended == true }) ||
                    (frame.physicsStep >= guardedStart + 10 && ids.all { frame.bodies[it]?.suspended == false })
            })
            assertTrue(alternatingMaxQueued.get() <= 1024, "alternating terrain queue grew beyond its admission bound: ${alternatingMaxQueued.get()}")
            println("terrain scan stress: invalidations=${invalidations.get() - beforeInvalidations}, fallbacks=${fallbacks.get() - beforeFallbacks}, maxQueued=${alternatingMaxQueued.get()}")
            if (fallbacks.get() > beforeFallbacks) assertTrue(ids.all { service.latestFrame(dimension).bodies[it]?.suspended == true },
                "fallback allowed an incompletely checked component to move")
            ids.forEach { service.submit(PhysicsCommand.ResumeBody(dimension, it)) }
            val resumed = service.latestFrame(dimension).physicsStep
            waitUntil {
                val frame = service.latestFrame(dimension)
                frame.physicsStep >= resumed + 10 && ids.all { frame.bodies[it]?.suspended == false }
            }
        } finally { service.close() }
    }

    @Test
    fun `shutdown closes publisher and worker even when checkpoint fails`() {
        val primary = java.io.IOException("checkpoint failed")
        val cleanup = java.io.IOException("publisher close failed")
        val closed = mutableListOf<String>()
        val error = kotlin.test.assertFailsWith<java.io.IOException> {
            closePhysicsRuntime(
                { throw primary },
                { closed += "publisher"; throw cleanup },
                { closed += "worker" }
            )
        }
        kotlin.test.assertSame(primary, error)
        assertEquals(listOf("publisher", "worker"), closed)
        assertTrue(cleanup in error.suppressed)
    }

    @Test
    fun `invalidated terrain retains collision for ordinary props until replacement`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:ordinary-collision-during-invalidation")
        val section = PhysicsSectionPosition(0, 0, 0)
        val wall = TerrainBlockGeometry(PhysicsBlockPosition(12, 8, 8), listOf(
            TerrainBox(PhysicsBox(PhysicsVector(12.0, 8.0, 8.0), PhysicsVector(13.0, 9.0, 9.0)))
        ))
        service.start()
        try {
            service.submit(PhysicsCommand.ConfigureWorlds(0.0, 1f, 18f))
            service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, section, listOf(wall)))
            service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, section))
            val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.5, 8.5)), linearVelocity = PhysicsVector(6.0, 0.0, 0.0))
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, definition))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            val initialStep = service.latestFrame(dimension).physicsStep
            waitUntil { service.latestFrame(dimension).physicsStep - initialStep >= 70 }
            val snapshot = requireNotNull(service.latestFrame(dimension).bodies[body])
            assertTrue(snapshot.transform.position.x in 8.5..11.6, "ordinary prop passed through invalidated wall: ${snapshot.transform.position.x}")
        } finally { service.close() }
    }

    @Test
    fun `guarded transform cannot rotate into uninstalled terrain`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:guarded-transform")
        val guard = AssemblyTerrainGuard(listOf(
            PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5)),
            PhysicsAabb(PhysicsVector(11.5, -0.5, -0.5), PhysicsVector(12.5, 0.5, 0.5))
        ), 0, 2)
        val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.0, 8.0)))
        val target = PhysicsTransform(definition.transform.position, PhysicsRotation(0.0, 1.0, 0.0, 0.0))
        val initial = requireNotNull(AssemblyTerrainCoverage.sections(guard, definition.transform, PhysicsVector.ZERO, PhysicsVector.ZERO))
        val swept = requireNotNull(AssemblyTerrainCoverage.calculate(guard, definition.transform, PhysicsVector.ZERO, PhysicsVector.ZERO, target = target)).sections
        assertTrue((swept - initial).isNotEmpty(), "fixture has no uninstalled swept terrain")
        service.start()
        try {
            initial.forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, definition, terrainGuard = guard))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            service.submit(PhysicsCommand.SetTransform(dimension, body, target))
            waitUntil { service.latestFrame(dimension).bodies[body]?.suspended == true }
            assertTrue(kotlin.math.abs(service.latestFrame(dimension).bodies.getValue(body).transform.rotation.w) > 0.99,
                "rotation reached uninstalled terrain")
            (swept - initial).forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            service.submit(PhysicsCommand.ResumeBody(dimension, body))
            service.submit(PhysicsCommand.SetTransform(dimension, body, target))
            waitUntil { service.latestFrame(dimension).bodies[body]?.transform?.rotation?.w?.let { kotlin.math.abs(it) < 0.01 } == true }
        } finally {
            service.close()
        }
    }

    @Test
    fun `global terrain budget rotates access among guarded bodies`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:terrain-budget-fairness")
        val box = PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))
        val guard = AssemblyTerrainGuard(List(100) { box }, 0, 2)
        val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.0, 8.0)), motionType = PhysicsMotionType.KINEMATIC)
        val coverage = requireNotNull(AssemblyTerrainCoverage.calculate(guard, definition.transform, PhysicsVector.ZERO, PhysicsVector.ZERO))
        assertTrue(coverage.visits * 10 > 16_384, "fixture does not exceed the global budget")
        service.start()
        try {
            coverage.sections.forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            repeat(10) { service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, definition, terrainGuard = guard)) }
            val ids = mutableSetOf<PhysicsBodyId>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { ids += it.bodyId }
                ids.size == 10
            }
            val observed = mutableSetOf<PhysicsBodyId>()
            waitUntil {
                val frame = service.latestFrame(dimension)
                ids.forEach { id ->
                    frame.bodies[id]?.let { snapshot ->
                        if (snapshot.suspended) service.submit(PhysicsCommand.ResumeBody(dimension, id)) else observed += id
                    }
                }
                observed.size == ids.size
            }
        } finally {
            service.close()
        }
    }

    @Test
    fun `over-budget staged assembly keeps its connected peer parked`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:staged-budget")
        val token = PhysicsRestoreToken()
        val guard = AssemblyTerrainGuard(listOf(PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))), 0, 2)
        val assembly = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.0, 8.0)), linearVelocity = PhysicsVector(1000.0, 0.0, 0.0))
        val peer = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(10.0, 8.0, 8.0)))
        val bodies = listOf(RestoreBody(UUID.randomUUID(), UUID.randomUUID(), assembly, guard), RestoreBody(UUID.randomUUID(), UUID.randomUUID(), peer))
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
        service.start()
        try {
            val baseline = requireNotNull(AssemblyTerrainCoverage.sections(guard, assembly.transform, PhysicsVector.ZERO, PhysicsVector.ZERO))
            baseline.forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            assertEquals(null, AssemblyTerrainCoverage.sections(guard, assembly.transform, assembly.linearVelocity, assembly.angularVelocity))
            service.submit(PhysicsCommand.RestoreComponent(dimension, bodies, listOf(RestoredJoint(0, 1, PhysicsJointKind.WELD, frames)), token))
            var staged: PhysicsAcknowledgement.ComponentStaged? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentStaged)?.let { staged = it }; staged != null }
            assertTrue(requireNotNull(staged).success)
            service.submit(PhysicsCommand.ActivateRestoredComponent(token))
            var activated: PhysicsAcknowledgement.ComponentActivated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentActivated)?.let { activated = it }; activated != null }
            assertFalse(requireNotNull(activated).success, "over-budget component was activated")
            val peerId = requireNotNull(staged).bodies[1].bodyId
            waitUntil { service.latestFrame(dimension).bodies.containsKey(peerId) }
            assertEquals(peer.transform.position, service.latestFrame(dimension).bodies[peerId]?.transform?.position)
        } finally {
            service.close()
        }
    }

    @Test
    fun `staged guarded component cannot activate before terrain is installed`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:staged-terrain-gate")
        val token = PhysicsRestoreToken()
        val section = PhysicsSectionPosition(0, 0, 0)
        val guard = AssemblyTerrainGuard(listOf(PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))), 0, 2)
        val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.0, 8.0)), linearVelocity = PhysicsVector(6.0, 0.0, 0.0))
        val body = RestoreBody(UUID.randomUUID(), UUID.randomUUID(), definition, guard)
        service.start()
        try {
            service.submit(PhysicsCommand.RestoreComponent(dimension, listOf(body), emptyList(), token))
            var staged: PhysicsAcknowledgement.ComponentStaged? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentStaged)?.let { staged = it }; staged != null }
            assertTrue(requireNotNull(staged).success)
            service.submit(PhysicsCommand.ActivateRestoredComponent(token))
            var activated: PhysicsAcknowledgement.ComponentActivated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentActivated)?.let { activated = it }; activated != null }
            assertFalse(requireNotNull(activated).success, "component activated without its terrain")
            requireNotNull(AssemblyTerrainCoverage.sections(guard, definition.transform, definition.linearVelocity, definition.angularVelocity))
                .forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            service.submit(PhysicsCommand.ActivateRestoredComponent(token))
            activated = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentActivated)?.let { activated = it }; activated != null }
            assertTrue(requireNotNull(activated).success, "component did not activate after terrain installation")
            val id = requireNotNull(staged).bodies.single().bodyId
            waitUntil { service.latestFrame(dimension).bodies[id]?.transform?.position?.x?.let { it > 8.1 } == true }
        } finally {
            service.close()
        }
    }

    @Test
    fun `guarded assembly waits for installed terrain and stops before invalidated terrain`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:terrain-gate")
        val prop = UUID.randomUUID()
        val section = PhysicsSectionPosition(0, 0, 0)
        val guard = AssemblyTerrainGuard(listOf(PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))), 0, 2)
        service.start()
        try {
            val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.0, 8.0)), linearVelocity = PhysicsVector(6.0, 0.0, 0.0))
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, definition, terrainGuard = guard))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).bodies[body]?.suspended == true }
            assertEquals(8.0, service.latestFrame(dimension).bodies[body]?.transform?.position?.x)
            repeat(3000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(1000 + it, 0, 0))) }
            requireNotNull(AssemblyTerrainCoverage.sections(guard, definition.transform, definition.linearVelocity, definition.angularVelocity))
                .forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, emptyList())) }
            service.submit(PhysicsCommand.ResumeBody(dimension, body))
            waitUntil { service.latestFrame(dimension).bodies[body]?.transform?.position?.x?.let { it > 8.1 } == true }
            repeat(3000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(1000 + it, 0, 0))) }
            service.submit(PhysicsCommand.InvalidateTerrainSection(dimension, section))
            val before = service.latestFrame(dimension)
            waitUntil { service.latestFrame(dimension).physicsStep > before.physicsStep }
            assertTrue(service.latestFrame(dimension).bodies[body]?.suspended == true, "body stepped into invalidated terrain")
        } finally {
            service.close()
        }
    }

    @Test
    fun `guarded body collides with terrain installed behind a backlog`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:guarded-collision")
        val section = PhysicsSectionPosition(0, 0, 0)
        val guard = AssemblyTerrainGuard(listOf(PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))), 0, 2)
        service.start()
        try {
            service.submit(PhysicsCommand.ConfigureWorlds(0.0, 1f, 18f))
            val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(8.0, 8.5, 8.5)), linearVelocity = PhysicsVector(6.0, 0.0, 0.0))
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, definition, terrainGuard = guard))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).bodies[body]?.suspended == true }
            repeat(3000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(1000 + it, 0, 0))) }
            val wall = TerrainBlockGeometry(PhysicsBlockPosition(12, 8, 8), listOf(
                TerrainBox(PhysicsBox(PhysicsVector(12.0, 8.0, 8.0), PhysicsVector(13.0, 9.0, 9.0)))
            ))
            requireNotNull(AssemblyTerrainCoverage.sections(guard, definition.transform, definition.linearVelocity, definition.angularVelocity))
                .forEach { service.submit(PhysicsCommand.ReplaceTerrainSection(dimension, it, if (it == section) listOf(wall) else emptyList())) }
            service.submit(PhysicsCommand.ResumeBody(dimension, body))
            val initialStep = service.latestFrame(dimension).physicsStep
            waitUntil { service.latestFrame(dimension).physicsStep - initialStep >= 70 }
            val snapshot = requireNotNull(service.latestFrame(dimension).bodies[body])
            assertTrue(snapshot.transform.position.x in 8.5..11.6, "guarded body passed through the installed wall: ${snapshot.transform.position.x}")
        } finally {
            service.close()
        }
    }

    @Test
    fun `oversized sweep holds the body rather than using partial coverage`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:terrain-budget")
        val bounds = PhysicsAabb(PhysicsVector(255.5, 255.5, 255.5), PhysicsVector(256.5, 256.5, 256.5))
        val guard = AssemblyTerrainGuard(listOf(bounds), -4, 20)
        val definition = bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(0.5, 80.0, 0.5)), angularVelocity = PhysicsVector(20.0, 20.0, 0.0))
        assertEquals(null, AssemblyTerrainCoverage.sections(guard, definition.transform, definition.linearVelocity, definition.angularVelocity))
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, definition, terrainGuard = guard))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).bodies[body]?.suspended == true }
            assertEquals(definition.transform.position, service.latestFrame(dimension).bodies[body]?.transform?.position)
        } finally {
            service.close()
        }
    }

    @Test
    fun `invalidation prevents staged and active components from advancing again`() {
        for (activateFirst in listOf(false, true)) {
            val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
            val dimension = PhysicsDimensionId("test:activation-race-$activateFirst")
            val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
            val bodies = listOf(
                RestoreBody(UUID.randomUUID(), UUID.randomUUID(), bodyDefinition().copy(linearVelocity = PhysicsVector(10.0, 0.0, 0.0))),
                RestoreBody(UUID.randomUUID(), UUID.randomUUID(), bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(2.0, 0.0, 0.0)), linearVelocity = PhysicsVector(10.0, 0.0, 0.0)))
            )
            val token = PhysicsRestoreToken()
            service.start()
            try {
                service.submit(PhysicsCommand.RestoreComponent(dimension, bodies, listOf(RestoredJoint(0, 1, PhysicsJointKind.WELD, frames)), token))
                var staged: PhysicsAcknowledgement.ComponentStaged? = null
                waitUntil {
                    (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentStaged)?.let { staged = it }
                    staged != null
                }
                val parkedBodies = requireNotNull(staged).bodies
                waitUntil { service.latestFrame(dimension).bodies.size == 2 }
                if (activateFirst) {
                    service.submit(PhysicsCommand.ActivateRestoredComponent(token))
                    var activated: PhysicsAcknowledgement.ComponentActivated? = null
                    waitUntil {
                        (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentActivated)?.let { activated = it }
                        activated != null
                    }
                    assertTrue(requireNotNull(activated).success)
                    waitUntil { service.latestFrame(dimension).bodies.getValue(parkedBodies[0].bodyId).transform.position.x > 0.01 }
                    repeat(5000) {
                        service.submit(PhysicsCommand.SetLinearVelocity(dimension, parkedBodies[0].bodyId, PhysicsVector(3.0, 0.0, 0.0)))
                    }
                }
                val request = UUID.randomUUID()
                val before = service.invalidateRestore(token, dimension, parkedBodies, request)
                if (!activateFirst) service.submit(PhysicsCommand.ActivateRestoredComponent(token))
                var parked: PhysicsAcknowledgement.ComponentParked? = null
                waitUntil {
                    (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentParked)?.let { parked = it }
                    parked != null
                }
                assertTrue(requireNotNull(parked).success)
                parkedBodies.forEach {
                    assertEquals(before.bodies.getValue(it.bodyId).transform.position.x,
                        requireNotNull(parked).snapshots.getValue(it.propId).transform.position.x, 0.0001)
                }
                waitUntil { service.latestFrame(dimension).bodies.isEmpty() }
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun `suspension takes effect before a step despite queued terrain commands`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:suspend-backlog")
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition()))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).bodies.containsKey(body) }
            repeat(5000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(it, 0, 0))) }
            service.submit(PhysicsCommand.SuspendBody(dimension, body))
            val step = service.latestFrame(dimension).physicsStep
            waitUntil { service.latestFrame(dimension).physicsStep > step }
            assertTrue(service.latestFrame(dimension).bodies[body]?.suspended == true,
                "body continued stepping while its suspension waited behind terrain commands")
        } finally {
            service.close()
        }
    }

    @Test
    fun `a queued spawn is suspended before its first published frame`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:spawn-before-suspend")
        val prop = UUID.randomUUID()
        service.start()
        try {
            repeat(5000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(it, 0, 0))) }
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition()))
            service.submit(PhysicsCommand.SuspendProp(dimension, prop))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).bodies.containsKey(body) }
            assertTrue(service.latestFrame(dimension).bodies[body]?.suspended == true,
                "new body stepped before its queued suspension")
        } finally {
            service.close()
        }
    }

    @Test
    fun `a suspended spawn cannot publish a moving body without a backlog`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:atomic-suspended-spawn")
        service.start()
        try {
            val prop = UUID.randomUUID()
            assertTrue(service.submitSuspendedSpawn(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition())))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).bodies.containsKey(body) }
            assertTrue(service.latestFrame(dimension).bodies[body]?.suspended == true,
                "suspended spawn published a moving body")
        } finally {
            service.close()
        }
    }

    @Test
    fun `a later suspension stays after an earlier queued resume`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:resume-before-suspend")
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition()))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            service.submit(PhysicsCommand.SuspendBody(dimension, body))
            waitUntil { service.latestFrame(dimension).bodies[body]?.suspended == true }
            repeat(5000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(it, 0, 0))) }
            service.submit(PhysicsCommand.ResumeBody(dimension, body))
            service.submit(PhysicsCommand.SuspendBody(dimension, body))
            val step = service.latestFrame(dimension).physicsStep
            waitUntil { service.latestFrame(dimension).physicsStep > step }
            assertTrue(service.latestFrame(dimension).bodies[body]?.suspended == true, "an older resume cancelled the new suspension")
            service.submit(PhysicsCommand.ResumeBody(dimension, body))
            waitUntil { service.latestFrame(dimension).bodies[body]?.suspended == false }
        } finally {
            service.close()
        }
    }

    @Test
    fun `destroy and replacement remain ordered before a queued suspension`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:replace-before-suspend")
        val prop = UUID.randomUUID()
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition()))
            var first: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { first = it }; first != null }
            val oldBody = requireNotNull(first).bodyId
            repeat(5000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(it, 0, 0))) }
            service.submit(PhysicsCommand.DestroyBody(dimension, oldBody))
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition()))
            service.submit(PhysicsCommand.SuspendProp(dimension, prop))
            var replacement: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { replacement = it }; replacement != null }
            val newBody = requireNotNull(replacement).bodyId
            waitUntil { service.latestFrame(dimension).bodies.containsKey(newBody) }
            assertTrue(service.latestFrame(dimension).bodies[newBody]?.suspended == true,
                "replacement body stepped before its queued suspension")
            service.submit(PhysicsCommand.DestroyBody(dimension, newBody))
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition()))
            var next: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { next = it }; next != null }
            val nextBody = requireNotNull(next).bodyId
            waitUntil { service.latestFrame(dimension).bodies[nextBody]?.suspended == false }
        } finally {
            service.close()
        }
    }

    @Test
    fun `suspension barriers do not advance the fixed clock`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:suspend-fixed-clock")
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition()))
            var created: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { created = it }; created != null }
            val body = requireNotNull(created).bodyId
            waitUntil { service.latestFrame(dimension).physicsStep > 0 }
            val first = service.latestFrame(dimension).physicsStep
            val start = System.nanoTime()
            while (System.nanoTime() - start < 300_000_000L) {
                service.submit(PhysicsCommand.SuspendBody(dimension, body))
                service.submit(PhysicsCommand.ResumeBody(dimension, body))
                Thread.sleep(1)
            }
            val elapsed = System.nanoTime() - start
            val steps = service.latestFrame(dimension).physicsStep - first
            assertTrue(steps <= elapsed / 1_000_000_000.0 * 60 + 10, "suspensions advanced the fixed clock")
        } finally {
            service.close()
        }
    }

    @Test
    fun `parking barrier cannot remove a newer body generation`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:park-generation")
        val prop = UUID.randomUUID()
        service.start()
        try {
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition()))
            var original: PhysicsAcknowledgement.BodyCreated? = null
            waitUntil { (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { original = it }; original != null }
            val oldBody = requireNotNull(original).bodyId
            repeat(5000) { service.submit(PhysicsCommand.RemoveTerrainSection(dimension, PhysicsSectionPosition(it, 0, 0))) }
            service.submit(PhysicsCommand.DestroyBody(dimension, oldBody))
            service.submit(PhysicsCommand.SpawnBody(prop, dimension, bodyDefinition()))
            val request = UUID.randomUUID()
            service.invalidateRestore(PhysicsRestoreToken(), dimension, listOf(ParkBody(prop, oldBody)), request)
            var replacement: PhysicsAcknowledgement.BodyCreated? = null
            var park: PhysicsAcknowledgement.ComponentParked? = null
            waitUntil {
                when (val ack = service.pollAcknowledgement()) {
                    is PhysicsAcknowledgement.BodyCreated -> replacement = ack
                    is PhysicsAcknowledgement.ComponentParked -> park = ack
                    else -> Unit
                }
                replacement != null && park != null
            }
            assertFalse(requireNotNull(park).success)
            val newBody = requireNotNull(replacement).bodyId
            waitUntil { service.latestFrame(dimension).bodies.containsKey(newBody) }
            assertFalse(service.latestFrame(dimension).bodies.getValue(newBody).suspended)
        } finally {
            service.close()
        }
    }

    @Test
    fun `component parking captures and removes every body in one physics operation`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:component-park")
        service.start()
        try {
            val props = List(2) { UUID.randomUUID() }
            props.forEach { service.submit(PhysicsCommand.SpawnBody(it, dimension, bodyDefinition())) }
            val created = mutableListOf<PhysicsAcknowledgement.BodyCreated>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let(created::add)
                created.size == 2
            }
            val request = UUID.randomUUID()
            service.submit(PhysicsCommand.ParkComponent(dimension, created.map { ParkBody(it.propId, it.bodyId) }, request))
            var parked: PhysicsAcknowledgement.ComponentParked? = null
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.ComponentParked)?.let { parked = it }
                parked != null
            }
            assertEquals(request, parked?.requestId)
            assertEquals(props.toSet(), requireNotNull(parked).snapshots.keys)
            waitUntil { service.latestFrame(dimension).bodies.isEmpty() }
        } finally {
            service.close()
        }
    }

    @Test
    fun `connection preparation stays parked until installation is submitted`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:connection-preparation")
        service.start()
        try {
            repeat(2) { index -> service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension,
                bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(index * 2.0, 4.0, 0.0))))) }
            val ids = mutableListOf<PhysicsBodyId>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { ids += it.bodyId }
                ids.size == 2
            }
            val request = UUID.randomUUID()
            service.submit(PhysicsCommand.PrepareConnection(dimension, ids[0], ids[1], request))
            var prepared: PhysicsAcknowledgement.ConnectionPrepared? = null
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.ConnectionPrepared)?.let { prepared = it }
                prepared != null
            }
            val frames = requireNotNull(prepared?.frames)
            assertEquals(ids.toSet(), requireNotNull(prepared).snapshots.values.map { it.bodyId }.toSet())
            waitUntil { ids.all { service.latestFrame(dimension).bodies[it]?.suspended == true } }
            repeat(10) { Thread.sleep(5) }
            assertTrue(ids.all { service.latestFrame(dimension).bodies[it]?.suspended == true })
            service.submit(PhysicsCommand.InstallPreparedConnection(dimension, ids[0], ids[1], request, PhysicsJointKind.WELD, frames, 0f))
            var installed: PhysicsAcknowledgement.ConnectionCreated? = null
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.ConnectionCreated)?.let { installed = it }
                installed != null
            }
            assertEquals(request, installed?.requestId)
            assertTrue(requireNotNull(installed).success)
            waitUntil { ids.all { service.latestFrame(dimension).bodies[it]?.suspended == false } }
        } finally {
            service.close()
        }
    }

    @Test
    fun `component restore acknowledges all bodies or none`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:component-restore")
        val frames = PhysicsJointFrames(PhysicsTransform(PhysicsVector(1.0, 0.0, 0.0)), PhysicsTransform(PhysicsVector(-1.0, 0.0, 0.0)))
        val bodies = listOf(
            RestoreBody(UUID.randomUUID(), UUID.randomUUID(), bodyDefinition()),
            RestoreBody(UUID.randomUUID(), UUID.randomUUID(), bodyDefinition().copy(transform = PhysicsTransform(PhysicsVector(2.0, 0.0, 0.0))))
        )
        val weld = RestoredJoint(0, 1, PhysicsJointKind.WELD, frames)
        service.start()
        try {
            service.submit(PhysicsCommand.RestoreComponent(dimension, bodies, listOf(weld, weld)))
            val failed = mutableListOf<PhysicsAcknowledgement.BodyCreationFailed>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreationFailed)?.let(failed::add)
                failed.size == 2
            }
            assertEquals(bodies.map { it.propId }.toSet(), failed.map { it.propId }.toSet())
            assertTrue(service.latestFrame(dimension).bodies.isEmpty())
            service.submit(PhysicsCommand.RestoreComponent(dimension, bodies, listOf(weld)))
            val created = mutableListOf<PhysicsAcknowledgement.BodyCreated>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let(created::add)
                created.size == 2
            }
            assertEquals(bodies.map { it.requestId }.toSet(), created.map { it.requestId }.toSet())
            waitUntil { service.latestFrame(dimension).bodies.size == 2 }
        } finally {
            service.close()
        }
    }

    @Test
    fun `connection acknowledgements report native success and missing bodies`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:connection-ack")
        service.start()
        try {
            repeat(2) { service.submit(PhysicsCommand.SpawnBody(UUID.randomUUID(), dimension, bodyDefinition())) }
            val bodies = mutableListOf<PhysicsBodyId>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.BodyCreated)?.let { bodies += it.bodyId }
                bodies.size == 2
            }
            val success = UUID.randomUUID()
            val failure = UUID.randomUUID()
            val filterSuccess = UUID.randomUUID()
            val invalidRope = UUID.randomUUID()
            service.submit(PhysicsCommand.CreateWeld(dimension, bodies[0], bodies[1], success))
            service.submit(PhysicsCommand.CreateAxis(dimension, bodies[0], PhysicsBodyId(999999), failure))
            service.submit(PhysicsCommand.CreateNoCollide(dimension, bodies[0], bodies[1], filterSuccess))
            service.submit(PhysicsCommand.CreateRope(dimension, bodies[0], bodies[1], 0f, invalidRope))
            val results = mutableListOf<PhysicsAcknowledgement.ConnectionCreated>()
            waitUntil {
                (service.pollAcknowledgement() as? PhysicsAcknowledgement.ConnectionCreated)?.let(results::add)
                results.size == 4
            }
            assertTrue(results.single { it.requestId == success }.success)
            assertFalse(results.single { it.requestId == failure }.success)
            assertTrue(results.single { it.requestId == filterSuccess }.success)
            assertFalse(results.single { it.requestId == invalidRope }.success)
        } finally {
            service.close()
        }
    }

    @Test
    fun `running state reflects a worker failure`() {
        val service = ServerPhysicsService(LoggerFactory.getLogger("tumble-test"))
        val dimension = PhysicsDimensionId("test:worker-failure")
        service.start()
        try {
            assertTrue(service.isRunning())
            service.submit(PhysicsCommand.CreateDimension(dimension))
            service.submit(PhysicsCommand.SetSubmersion(dimension, PhysicsBodyId(1), 2f))
            waitUntil { service.debugState().state == PhysicsServiceState.FAILED }
            assertFalse(service.isRunning())
        } finally {
            service.close()
        }
    }

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
