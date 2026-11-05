// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.tumble.physics.PhysicsBodyId
import cc.modlabs.tumble.physics.PhysicsWorld
import cc.modlabs.tumble.physics.PhysicsWorldConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.slf4j.Logger

class ServerPhysicsService(private val logger: Logger) : AutoCloseable {
    private val commands = ConcurrentLinkedQueue<PhysicsCommand>()
    private val acknowledgements = ConcurrentLinkedQueue<PhysicsAcknowledgement>()
    private val impacts = ConcurrentLinkedQueue<PhysicsImpactNotification>()
    private val impactCount = AtomicInteger()
    private val frames = ConcurrentHashMap<PhysicsDimensionId, AtomicReference<PhysicsSnapshotFrame>>()
    private val state = AtomicReference(PhysicsServiceState.NEW)
    private val failure = AtomicReference<String?>()
    private val dimensionCount = AtomicInteger()
    private val bodyCount = AtomicInteger()
    private val terrainCount = AtomicInteger()
    private val completedSteps = AtomicLong()
    private val workNanos = AtomicLong()
    private var worldTuning = PhysicsWorldTuning()
    private val thread = Thread(::runLoop, "Project Tumble Physics").apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> fail(error) }
    }

    fun start() {
        check(state.compareAndSet(PhysicsServiceState.NEW, PhysicsServiceState.RUNNING)) {
            "Physics service can only be started once"
        }
        thread.start()
    }

    fun isRunning() = state.get() == PhysicsServiceState.RUNNING

    fun submit(command: PhysicsCommand): Boolean {
        if (state.get() != PhysicsServiceState.RUNNING) return false
        commands += command
        LockSupport.unpark(thread)
        return true
    }

    fun latestFrame(dimension: PhysicsDimensionId): PhysicsSnapshotFrame =
        frames[dimension]?.get() ?: PhysicsSnapshotFrame.EMPTY

    fun pollAcknowledgement(): PhysicsAcknowledgement? = acknowledgements.poll()

    fun pollImpact(): PhysicsImpactNotification? = impacts.poll()?.also { impactCount.decrementAndGet() }

    fun debugState(): PhysicsServiceDebugState {
        val steps = completedSteps.get()
        return PhysicsServiceDebugState(
            state.get(),
            dimensionCount.get(),
            bodyCount.get(),
            terrainCount.get(),
            commands.size,
            thread.isAlive,
            if (steps == 0L) 0.0 else workNanos.get() / steps / 1_000_000.0,
            failure.get()
        )
    }

    private fun runLoop() {
        val worlds = mutableMapOf<PhysicsDimensionId, PhysicsWorld>()
        val bodies = mutableMapOf<UUID, RuntimeBody>()
        val terrain = mutableMapOf<PhysicsDimensionId, MutableSet<cc.modlabs.tumble.physics.PhysicsSectionPosition>>()
        val interval = 1_000_000_000L / 60L
        var nextStep = System.nanoTime()
        var step = 0L
        try {
            while (state.get() == PhysicsServiceState.RUNNING) {
                val remaining = nextStep - System.nanoTime()
                if (remaining > 0) {
                    LockSupport.parkNanos(remaining)
                    continue
                }
                val started = System.nanoTime()
                drainCommands(worlds, bodies, terrain)
                if (state.get() != PhysicsServiceState.RUNNING) break
                step++
                worlds.forEach { (dimension, world) ->
                    frames.computeIfAbsent(dimension) { AtomicReference(PhysicsSnapshotFrame.EMPTY) }
                        .set(PhysicsSnapshotFrame.of(step, world.stepAndSnapshots().associateBy { it.bodyId }))
                    world.consumeImpactEvents().forEach { event -> publishImpact(PhysicsImpactNotification(dimension, event)) }
                }
                completedSteps.incrementAndGet()
                workNanos.addAndGet(System.nanoTime() - started)
                nextStep += interval
                if (nextStep - System.nanoTime() < -interval * 4) nextStep = System.nanoTime()
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            worlds.values.forEach { runCatching { it.close() } }
            worlds.clear()
            bodies.clear()
            terrain.clear()
            frames.clear()
            commands.clear()
            impacts.clear()
            impactCount.set(0)
            dimensionCount.set(0)
            bodyCount.set(0)
            terrainCount.set(0)
            state.compareAndSet(PhysicsServiceState.STOPPING, PhysicsServiceState.STOPPED)
        }
    }

    private fun drainCommands(
        worlds: MutableMap<PhysicsDimensionId, PhysicsWorld>,
        bodies: MutableMap<UUID, RuntimeBody>,
        terrain: MutableMap<PhysicsDimensionId, MutableSet<cc.modlabs.tumble.physics.PhysicsSectionPosition>>
    ) {
        var processed = 0
        while (true) {
            if (processed++ >= MAX_COMMANDS_PER_STEP) return
            when (val command = commands.poll() ?: return) {
                is PhysicsCommand.CreateDimension -> worlds.getOrPut(command.dimension, ::createWorld)
                is PhysicsCommand.RemoveDimension -> {
                    worlds.remove(command.dimension)?.close()
                    bodies.entries.removeIf { it.value.dimension == command.dimension }
                    terrain.remove(command.dimension)
                    frames.remove(command.dimension)
                }
                is PhysicsCommand.SpawnBody -> try {
                    val world = worlds.getOrPut(command.dimension, ::createWorld)
                    val bodyId = world.createBody(command.definition)
                    bodies[command.propId] = RuntimeBody(command.dimension, bodyId)
                    acknowledgements += PhysicsAcknowledgement.BodyCreated(command.propId, command.dimension, bodyId, command.requestId)
                } catch (error: Throwable) {
                    acknowledgements += PhysicsAcknowledgement.BodyCreationFailed(
                        command.propId,
                        error.message ?: error::class.java.simpleName,
                        command.requestId
                    )
                }
                is PhysicsCommand.DestroyBody -> {
                    worlds[command.dimension]?.destroyBody(command.bodyId)
                    bodies.entries.removeIf {
                        it.value.dimension == command.dimension && it.value.bodyId == command.bodyId
                    }
                }
                is PhysicsCommand.CreateWeld -> worlds[command.dimension]
                    ?.createWeld(command.bodyA, command.bodyB)
                is PhysicsCommand.DestroyWelds -> worlds[command.dimension]
                    ?.destroyWelds(command.bodyId)
                is PhysicsCommand.DestroyWeld -> worlds[command.dimension]
                    ?.destroyWeld(command.bodyA, command.bodyB)
                is PhysicsCommand.CreateRope -> {
                    if (command.length.isFinite() && command.length > 0f) {
                        worlds[command.dimension]?.createRope(command.bodyA, command.bodyB, command.length)
                    } else logger.warn("Ignoring rope with invalid length {}", command.length)
                }
                is PhysicsCommand.CreateAxis -> worlds[command.dimension]
                    ?.createAxis(command.bodyA, command.bodyB)
                is PhysicsCommand.CreateNoCollide -> worlds[command.dimension]
                    ?.createNoCollide(command.bodyA, command.bodyB)
                is PhysicsCommand.DestroyToolConstraints -> worlds[command.dimension]
                    ?.destroyToolConstraints(command.bodyId)
                is PhysicsCommand.ReplaceTerrainSection -> worlds.getOrPut(command.dimension, ::createWorld).let { world ->
                    world.replaceTerrainSection(command.section, command.geometry)
                    terrain.getOrPut(command.dimension, ::mutableSetOf).apply {
                        if (command.geometry.isEmpty()) remove(command.section) else add(command.section)
                    }
                }
                is PhysicsCommand.RemoveTerrainSection -> {
                    worlds[command.dimension]?.removeTerrainSection(command.section)
                    terrain[command.dimension]?.remove(command.section)
                }
                is PhysicsCommand.WakeBody -> worlds[command.dimension]?.wake(command.bodyId)
                is PhysicsCommand.SuspendBody -> worlds[command.dimension]?.suspend(command.bodyId)
                is PhysicsCommand.SuspendProp -> bodies[command.propId]?.takeIf { it.dimension == command.dimension }?.let {
                    worlds[command.dimension]?.suspend(it.bodyId)
                }
                is PhysicsCommand.ResumeBody -> worlds[command.dimension]?.resume(command.bodyId)
                is PhysicsCommand.SetMotionType -> worlds[command.dimension]
                    ?.setMotionType(command.bodyId, command.motionType)
                is PhysicsCommand.ApplyCentralImpulse -> worlds[command.dimension]
                    ?.applyCentralImpulse(command.bodyId, command.impulse)
                is PhysicsCommand.SetLinearVelocity -> worlds[command.dimension]
                    ?.setLinearVelocity(command.bodyId, command.velocity)
                is PhysicsCommand.SetTransform -> worlds[command.dimension]
                    ?.setTransform(command.bodyId, command.transform)
                is PhysicsCommand.AddLinearVelocity -> worlds[command.dimension]
                    ?.addLinearVelocity(command.bodyId, command.delta)
                is PhysicsCommand.SetSubmersion -> worlds[command.dimension]
                    ?.setSubmersion(command.bodyId, command.fraction)
                is PhysicsCommand.CreateTemporaryKinematicBody -> worlds[command.dimension]
                    ?.createTemporaryKinematicBody(
                        command.definition,
                        command.target,
                        command.travelSeconds,
                        command.lifetimeSteps
                    )
                is PhysicsCommand.ConfigureWorlds -> {
                    worldTuning = PhysicsWorldTuning(
                        command.gravityY,
                        command.buoyancy,
                        command.impactSpeedThreshold
                    )
                    worlds.values.forEach { world -> configureWorld(world, worldTuning) }
                }
                PhysicsCommand.Stop -> {
                    state.compareAndSet(PhysicsServiceState.RUNNING, PhysicsServiceState.STOPPING)
                    updateCounts(worlds, bodies, terrain)
                    return
                }
            }
            updateCounts(worlds, bodies, terrain)
        }
    }

    private fun updateCounts(
        worlds: Map<PhysicsDimensionId, PhysicsWorld>,
        bodies: Map<UUID, RuntimeBody>,
        terrain: Map<PhysicsDimensionId, Set<cc.modlabs.tumble.physics.PhysicsSectionPosition>>
    ) {
        dimensionCount.set(worlds.size)
        bodyCount.set(bodies.size)
        terrainCount.set(terrain.values.sumOf(Set<*>::size))
    }

    private fun fail(error: Throwable) {
        failure.compareAndSet(null, error.stackTraceToString())
        state.set(PhysicsServiceState.FAILED)
        logger.error("Project Tumble physics thread failed", error)
    }

    private fun createWorld(): PhysicsWorld = PhysicsWorld(
        PhysicsWorldConfig(
            gravity = PhysicsVector(0.0, worldTuning.gravityY, 0.0),
            impactSpeedThreshold = worldTuning.impactSpeedThreshold
        )
    ).also { it.setBuoyancyForceScale(worldTuning.buoyancy) }

    private fun configureWorld(world: PhysicsWorld, tuning: PhysicsWorldTuning) {
        world.setGravity(PhysicsVector(0.0, tuning.gravityY, 0.0))
        world.setBuoyancyForceScale(tuning.buoyancy)
        world.setImpactSpeedThreshold(tuning.impactSpeedThreshold)
    }

    private fun publishImpact(notification: PhysicsImpactNotification) {
        if (impactCount.incrementAndGet() > MAX_QUEUED_IMPACTS) {
            impactCount.decrementAndGet()
            return
        }
        impacts += notification
    }

    override fun close() {
        when (state.get()) {
            PhysicsServiceState.NEW -> state.set(PhysicsServiceState.STOPPED)
            PhysicsServiceState.RUNNING -> {
                state.compareAndSet(PhysicsServiceState.RUNNING, PhysicsServiceState.STOPPING)
                LockSupport.unpark(thread)
            }
            else -> Unit
        }
        if (thread.isAlive) {
            try {
                thread.join(5_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("Interrupted while waiting for the Project Tumble physics thread to stop")
            }
            if (thread.isAlive) logger.error("Project Tumble physics thread did not stop within 5 seconds")
        }
    }

    private data class RuntimeBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId)
    private data class PhysicsWorldTuning(
        val gravityY: Double = -32.0,
        val buoyancy: Float = 1.5f,
        val impactSpeedThreshold: Float = 18f
    )

    private companion object {
        const val MAX_QUEUED_IMPACTS = 1024
        const val MAX_COMMANDS_PER_STEP = 256
    }
}
