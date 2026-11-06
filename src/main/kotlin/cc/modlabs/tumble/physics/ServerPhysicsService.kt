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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.Logger

class ServerPhysicsService(private val logger: Logger) : AutoCloseable {
    private val stepBoundary = ReentrantLock(true)
    private val barrierRoom = stepBoundary.newCondition()
    private val pendingSuspensions = AtomicInteger()
    private val commands = ConcurrentLinkedQueue<QueuedCommand>()
    // Guarded by stepBoundary; leave reserved capacity for safety barriers.
    private var pendingCommands = 0
    private val pendingTerrainInvalidations = mutableMapOf<Pair<PhysicsDimensionId, PhysicsSectionPosition>, Long>()
    private var nextInvalidationGeneration = 0L
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
    private val terrainRevision = AtomicLong()
    private val appliedTerrainRevision = AtomicLong()
    private val terrainScanInvalidations = AtomicLong()
    private val terrainScanFallbacks = AtomicLong()
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
        val barrier = command is PhysicsCommand.SuspendBody || command is PhysicsCommand.SuspendProp ||
            command is PhysicsCommand.ParkComponent || command is PhysicsCommand.InvalidateTerrainSection
        val terrainKey = when (command) {
            is PhysicsCommand.InvalidateTerrainSection -> command.dimension to command.section
            is PhysicsCommand.ReplaceTerrainSection -> command.dimension to command.section
            is PhysicsCommand.RemoveTerrainSection -> command.dimension to command.section
            else -> null
        }
        stepBoundary.withLock {
            val limit = if (barrier) MAX_PENDING_COMMANDS else MAX_NORMAL_COMMANDS
            while (true) {
                if (state.get() != PhysicsServiceState.RUNNING) return false
                if (command is PhysicsCommand.InvalidateTerrainSection && terrainKey in pendingTerrainInvalidations) return true
                if (pendingCommands < limit) break
                barrierRoom.awaitUninterruptibly()
            }
            val generation = if (command is PhysicsCommand.InvalidateTerrainSection) {
                (++nextInvalidationGeneration).also { pendingTerrainInvalidations[requireNotNull(terrainKey)] = it }
            } else {
                if (terrainKey != null) pendingTerrainInvalidations.remove(terrainKey)
                0L
            }
            if (barrier) pendingSuspensions.incrementAndGet()
            commands += QueuedCommand(command, generation)
            pendingCommands++
        }
        LockSupport.unpark(thread)
        return true
    }

    fun submitSuspendedSpawn(command: PhysicsCommand.SpawnBody): Boolean {
        stepBoundary.withLock {
            while (pendingCommands > MAX_PENDING_COMMANDS - 2 && state.get() == PhysicsServiceState.RUNNING)
                barrierRoom.awaitUninterruptibly()
            if (state.get() != PhysicsServiceState.RUNNING) return false
            commands += QueuedCommand(command)
            pendingSuspensions.incrementAndGet()
            commands += QueuedCommand(PhysicsCommand.SuspendProp(command.dimension, command.propId))
            pendingCommands += 2
        }
        LockSupport.unpark(thread)
        return true
    }

    fun invalidateRestore(token: PhysicsRestoreToken, dimension: PhysicsDimensionId, bodies: List<ParkBody>, requestId: UUID): PhysicsSnapshotFrame =
        invalidateRestores(listOf(token), dimension, bodies, requestId)

    fun invalidateRestores(tokens: Collection<PhysicsRestoreToken>, dimension: PhysicsDimensionId, bodies: List<ParkBody>, requestId: UUID): PhysicsSnapshotFrame =
        stepBoundary.withLock {
            tokens.forEach { it.valid = false }
            while (bodies.isNotEmpty() && pendingCommands >= MAX_PENDING_COMMANDS && state.get() == PhysicsServiceState.RUNNING)
                barrierRoom.awaitUninterruptibly()
            val before = latestFrame(dimension)
            if (bodies.isNotEmpty() && state.get() == PhysicsServiceState.RUNNING) {
                pendingSuspensions.incrementAndGet()
                commands += QueuedCommand(PhysicsCommand.ParkComponent(dimension, bodies, requestId))
                pendingCommands++
            }
            LockSupport.unpark(thread)
            before
        }

    fun latestFrame(dimension: PhysicsDimensionId): PhysicsSnapshotFrame =
        frames[dimension]?.get() ?: PhysicsSnapshotFrame.EMPTY

    fun pollAcknowledgement(): PhysicsAcknowledgement? = acknowledgements.poll()
    fun nextTerrainRevision() = terrainRevision.incrementAndGet()
    fun appliedTerrainRevision() = appliedTerrainRevision.get()

    private fun terrainReady(
        guard: AssemblyTerrainGuard,
        transform: PhysicsTransform,
        velocity: PhysicsVector,
        angular: PhysicsVector,
        installed: Set<PhysicsSectionPosition>?
    ): Boolean = AssemblyTerrainCoverage.sections(guard, transform, velocity, angular)
        ?.let { installed.orEmpty().containsAll(it) } == true

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
        val restored = mutableMapOf<PhysicsRestoreToken, RestoredRuntimeComponent>()
        val installedTerrain = mutableMapOf<PhysicsDimensionId, MutableSet<PhysicsSectionPosition>>()
        val terrainBlocked = mutableSetOf<UUID>()
        val interval = 1_000_000_000L / 60L
        var nextStep = System.nanoTime()
        var step = 0L
        var terrainScan: TerrainScan? = null
        val terrainScanRestarts = TerrainScanRestarts(MAX_TERRAIN_SCAN_RESTARTS)
        fun invalidateScan() {
            if (terrainScan != null) {
                terrainScan = null
                terrainScanRestarts.invalidated()
                terrainScanInvalidations.incrementAndGet()
            }
        }
        try {
            while (state.get() == PhysicsServiceState.RUNNING) {
                val remaining = nextStep - System.nanoTime()
                if (remaining > 0 && pendingSuspensions.get() == 0) {
                    LockSupport.parkNanos(remaining)
                    continue
                }
                val started = System.nanoTime()
                val stepped = stepBoundary.withLock {
                    if (drainCommands(worlds, bodies, terrain, restored, installedTerrain, terrainBlocked)) invalidateScan()
                    // Stop new barriers from extending this prefix indefinitely while it drains.
                    while (pendingSuspensions.get() != 0 && state.get() == PhysicsServiceState.RUNNING) {
                        if (drainCommands(worlds, bodies, terrain, restored, installedTerrain, terrainBlocked)) invalidateScan()
                    }
                    if (state.get() != PhysicsServiceState.RUNNING || System.nanoTime() < nextStep) false else {
                        restored.forEach { (token, group) ->
                            if (!token.valid && group.active) group.bodies.forEach { worlds[group.dimension]?.suspend(it.bodyId) }
                        }
                        if (terrainScanRestarts.shouldPark()) {
                            terrainScanFallbacks.incrementAndGet()
                            // A continuously changing scan cannot safely admit movement. Park its
                            // guarded components, then let unrelated bodies make progress.
                            bodies.forEach { (propId, body) ->
                                if (!body.staged && body.guard != null) {
                                    val world = worlds[body.dimension]
                                    if (world?.snapshot(body.bodyId)?.suspended == false) {
                                        world.suspend(body.bodyId)
                                        if (terrainBlocked.add(propId)) acknowledgements += PhysicsAcknowledgement.AssemblyTerrainBlocked(propId, body.dimension, body.bodyId)
                                    }
                                }
                            }
                            terrainScanRestarts.reset()
                        }
                        val scan = terrainScan ?: run {
                            val guarded = bodies.entries.filter { !it.value.staged && it.value.guard != null }
                                .map { it.key to it.value }
                            TerrainScan(guarded, if (guarded.isEmpty()) 0 else (step % guarded.size).toInt())
                                .also { terrainScan = it }
                        }
                        var remainingTerrainChecks = MAX_TERRAIN_CHECKS_PER_STEP
                        while (scan.next < scan.bodies.size && remainingTerrainChecks >= AssemblyTerrainCoverage.MAX_SECTIONS) {
                            val (propId, body) = scan.bodies[(scan.first + scan.next++) % scan.bodies.size]
                            val guard = requireNotNull(body.guard)
                            val world = worlds[body.dimension] ?: continue
                            val snapshot = world.snapshot(body.bodyId) ?: continue
                            if (snapshot.suspended) continue
                            val coverage = AssemblyTerrainCoverage.calculate(guard, snapshot.transform, snapshot.linearVelocity, snapshot.angularVelocity)
                            remainingTerrainChecks -= if (coverage == null) AssemblyTerrainCoverage.MAX_SECTIONS else maxOf(1, coverage.visits)
                            if (coverage == null || !installedTerrain[body.dimension].orEmpty().containsAll(coverage.sections)) {
                                world.suspend(body.bodyId)
                                if (terrainBlocked.add(propId)) acknowledgements += PhysicsAcknowledgement.AssemblyTerrainBlocked(propId, body.dimension, body.bodyId)
                            }
                        }
                        if (scan.next < scan.bodies.size) false else {
                            terrainScan = null
                            terrainScanRestarts.reset()
                            step++
                            worlds.forEach { (dimension, world) ->
                                frames.computeIfAbsent(dimension) { AtomicReference(PhysicsSnapshotFrame.EMPTY) }
                                    .set(PhysicsSnapshotFrame.of(step, world.stepAndSnapshots().associateBy { it.bodyId }))
                                world.consumeImpactEvents().forEach { event -> publishImpact(PhysicsImpactNotification(dimension, event)) }
                            }
                            true
                        }
                    }
                }
                if (!stepped) continue
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
            installedTerrain.clear()
            terrainBlocked.clear()
            frames.clear()
            stepBoundary.withLock {
                commands.clear()
                pendingCommands = 0
                pendingTerrainInvalidations.clear()
                barrierRoom.signalAll()
            }
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
        terrain: MutableMap<PhysicsDimensionId, MutableSet<cc.modlabs.tumble.physics.PhysicsSectionPosition>>,
        restored: MutableMap<PhysicsRestoreToken, RestoredRuntimeComponent>,
        installedTerrain: MutableMap<PhysicsDimensionId, MutableSet<PhysicsSectionPosition>>,
        terrainBlocked: MutableSet<UUID>
    ): Boolean {
        var processed = 0
        var invalidated = false
        while (true) {
            if (processed >= MAX_COMMANDS_PER_STEP) return invalidated
            val queued = commands.poll() ?: return invalidated
            pendingCommands--
            barrierRoom.signalAll()
            val command = queued.command
            processed++
            fun guarded(dimension: PhysicsDimensionId, id: PhysicsBodyId) =
                bodies.values.any { it.dimension == dimension && it.bodyId == id && it.guard != null }
            invalidated = invalidated || when (command) {
                is PhysicsCommand.WakeBody, is PhysicsCommand.SetSubmersion, is PhysicsCommand.ConfigureWorlds -> false
                is PhysicsCommand.SetLinearVelocity -> guarded(command.dimension, command.bodyId)
                is PhysicsCommand.AddLinearVelocity -> guarded(command.dimension, command.bodyId)
                is PhysicsCommand.ApplyCentralImpulse -> guarded(command.dimension, command.bodyId)
                is PhysicsCommand.SetTransform -> guarded(command.dimension, command.bodyId)
                else -> true
            }
            when (command) {
                is PhysicsCommand.CreateDimension -> worlds.getOrPut(command.dimension, ::createWorld)
                is PhysicsCommand.RemoveDimension -> {
                    worlds.remove(command.dimension)?.close()
                    bodies.filterValues { it.dimension == command.dimension }.keys.forEach(terrainBlocked::remove)
                    bodies.entries.removeIf { it.value.dimension == command.dimension }
                    terrain.remove(command.dimension)
                    installedTerrain.remove(command.dimension)
                    frames.remove(command.dimension)
                    restored.entries.removeIf { it.value.dimension == command.dimension }
                }
                is PhysicsCommand.SpawnBody -> try {
                    val world = worlds.getOrPut(command.dimension, ::createWorld)
                    val bodyId = world.createBody(command.definition)
                    bodies[command.propId] = RuntimeBody(command.dimension, bodyId, command.terrainGuard)
                    val guard = command.terrainGuard
                    val blocked = guard != null && !terrainReady(guard, command.definition.transform, command.definition.linearVelocity,
                        command.definition.angularVelocity, installedTerrain[command.dimension])
                    if (blocked) { world.suspend(bodyId); terrainBlocked += command.propId }
                    acknowledgements += PhysicsAcknowledgement.BodyCreated(command.propId, command.dimension, bodyId, command.requestId, blocked)
                } catch (error: Throwable) {
                    acknowledgements += PhysicsAcknowledgement.BodyCreationFailed(
                        command.propId,
                        error.message ?: error::class.java.simpleName,
                        command.requestId
                    )
                }
                is PhysicsCommand.ParkComponent -> {
                    val world = worlds[command.dimension]
                    val valid = world != null && command.bodies.isNotEmpty() &&
                        command.bodies.map { it.propId }.toSet().size == command.bodies.size &&
                        command.bodies.all { bodies[it.propId]?.let { runtime -> runtime.dimension == command.dimension && runtime.bodyId == it.bodyId } == true && world.snapshot(it.bodyId) != null }
                    if (!valid) acknowledgements += PhysicsAcknowledgement.ComponentParked(command.requestId, emptyMap(), false)
                    else {
                        val snapshots = command.bodies.associate { it.propId to requireNotNull(world.snapshot(it.bodyId)) }
                        command.bodies.forEach { world.destroyBody(it.bodyId); bodies.remove(it.propId); terrainBlocked.remove(it.propId) }
                        restored.entries.removeIf { (_, group) -> group.dimension == command.dimension && group.bodies.all { body -> body in command.bodies } }
                        acknowledgements += PhysicsAcknowledgement.ComponentParked(command.requestId, snapshots, true)
                    }
                    pendingSuspensions.decrementAndGet()
                    barrierRoom.signalAll()
                }
                is PhysicsCommand.RestoreComponent -> {
                    val ids = command.bodies.map { it.propId }
                    val requests = command.bodies.map { it.requestId }
                    val result = try {
                        if (ids.isEmpty() || ids.toSet().size != ids.size || requests.toSet().size != requests.size || ids.any(bodies::containsKey) || command.token?.valid == false) null
                        else worlds.getOrPut(command.dimension, ::createWorld).restoreComponent(command.bodies.map { it.definition }, command.joints, command.token == null)
                    } catch (error: Throwable) {
                        logger.error("Failed to restore physics component", error)
                        null
                    }
                    if (result == null) {
                        if (command.token != null) acknowledgements += PhysicsAcknowledgement.ComponentStaged(command.token, emptyList(), false)
                        else command.bodies.forEach {
                            acknowledgements += PhysicsAcknowledgement.BodyCreationFailed(it.propId, "Component restore failed", it.requestId)
                        }
                    } else {
                        val parked = command.bodies.zip(result).map { (body, id) ->
                            bodies[body.propId] = RuntimeBody(command.dimension, id, body.terrainGuard, command.token != null)
                            ParkBody(body.propId, id)
                        }
                        if (command.token != null) {
                            restored[command.token] = RestoredRuntimeComponent(command.dimension, parked, command.bodies.map { it.definition })
                            acknowledgements += PhysicsAcknowledgement.ComponentStaged(command.token, parked, true)
                        } else command.bodies.zip(result).forEach { (body, id) ->
                            acknowledgements += PhysicsAcknowledgement.BodyCreated(body.propId, command.dimension, id, body.requestId)
                        }
                    }
                }
                is PhysicsCommand.ActivateRestoredComponent -> {
                    val group = restored[command.token]
                    val success = command.token.valid && group != null && !group.active &&
                        group.bodies.zip(group.definitions).all { (body, definition) ->
                            val guard = bodies[body.propId]?.guard
                            guard == null || terrainReady(guard, definition.transform, definition.linearVelocity, definition.angularVelocity, installedTerrain[group.dimension])
                        } &&
                        worlds[group.dimension]?.activateRestoredComponent(group.bodies.map { it.bodyId }, group.definitions) == true
                    if (success) {
                        group!!.active = true
                        group.bodies.forEach { bodies[it.propId]?.staged = false }
                    }
                    acknowledgements += PhysicsAcknowledgement.ComponentActivated(command.token, success)
                }
                is PhysicsCommand.PrepareConnection -> {
                    val world = worlds[command.dimension]
                    val frames = world?.prepareConnection(command.requestId, command.bodyA, command.bodyB)
                    val snapshots = if (frames != null) world.preparedConnectionSnapshots(command.requestId).orEmpty() else emptyList()
                    val owners = bodies.entries.filter { it.value.dimension == command.dimension }.associate { it.value.bodyId to it.key }
                    if (frames != null && snapshots.any { it.bodyId !in owners }) {
                        world.cancelConnectionPreparation(command.requestId)
                        acknowledgements += PhysicsAcknowledgement.ConnectionPrepared(command.requestId, null)
                    } else {
                        acknowledgements += PhysicsAcknowledgement.ConnectionPrepared(command.requestId, frames,
                            snapshots.associate { owners.getValue(it.bodyId) to it })
                    }
                }
                is PhysicsCommand.InstallPreparedConnection -> {
                    val success = worlds[command.dimension]?.installPreparedConnection(
                        command.requestId, command.bodyA, command.bodyB, command.kind, command.frames, command.ropeLength
                    ) == true
                    acknowledgements += PhysicsAcknowledgement.ConnectionCreated(command.requestId, success)
                }
                is PhysicsCommand.CancelConnectionPreparation -> worlds[command.dimension]?.cancelConnectionPreparation(command.requestId)
                is PhysicsCommand.DestroyBody -> {
                    worlds[command.dimension]?.destroyBody(command.bodyId)
                    bodies.filterValues { it.dimension == command.dimension && it.bodyId == command.bodyId }.keys.forEach(terrainBlocked::remove)
                    bodies.entries.removeIf {
                        it.value.dimension == command.dimension && it.value.bodyId == command.bodyId
                    }
                }
                is PhysicsCommand.CreateWeld -> {
                    val success = worlds[command.dimension]?.createWeld(command.bodyA, command.bodyB) == true
                    command.requestId?.let { acknowledgements += PhysicsAcknowledgement.ConnectionCreated(it, success) }
                }
                is PhysicsCommand.DestroyWelds -> worlds[command.dimension]
                    ?.destroyWelds(command.bodyId)
                is PhysicsCommand.DestroyWeld -> worlds[command.dimension]
                    ?.destroyWeld(command.bodyA, command.bodyB)
                is PhysicsCommand.CreateRope -> {
                    val success = if (command.length.isFinite() && command.length > 0f) {
                        worlds[command.dimension]?.createRope(command.bodyA, command.bodyB, command.length) == true
                    } else {
                        logger.warn("Ignoring rope with invalid length {}", command.length)
                        false
                    }
                    command.requestId?.let { acknowledgements += PhysicsAcknowledgement.ConnectionCreated(it, success) }
                }
                is PhysicsCommand.CreateAxis -> {
                    val success = worlds[command.dimension]?.createAxis(command.bodyA, command.bodyB) == true
                    command.requestId?.let { acknowledgements += PhysicsAcknowledgement.ConnectionCreated(it, success) }
                }
                is PhysicsCommand.CreateNoCollide -> {
                    val success = worlds[command.dimension]?.createNoCollide(command.bodyA, command.bodyB) == true
                    command.requestId?.let { acknowledgements += PhysicsAcknowledgement.ConnectionCreated(it, success) }
                }
                is PhysicsCommand.DestroyToolConstraints -> worlds[command.dimension]
                    ?.destroyToolConstraints(command.bodyId)
                is PhysicsCommand.ReplaceTerrainSection -> worlds.getOrPut(command.dimension, ::createWorld).let { world ->
                    world.replaceTerrainSection(command.section, command.geometry)
                    if (command.revision > 0) appliedTerrainRevision.set(command.revision)
                    installedTerrain.getOrPut(command.dimension, ::mutableSetOf).add(command.section)
                    terrain.getOrPut(command.dimension, ::mutableSetOf).apply {
                        if (command.geometry.isEmpty()) remove(command.section) else add(command.section)
                    }
                }
                is PhysicsCommand.RemoveTerrainSection -> {
                    worlds[command.dimension]?.removeTerrainSection(command.section)
                    terrain[command.dimension]?.remove(command.section)
                    installedTerrain[command.dimension]?.remove(command.section)
                }
                is PhysicsCommand.InvalidateTerrainSection -> {
                    // Keep the last collision geometry until replacement. Unguarded ordinary
                    // props still rely on it while guarded assemblies are held below.
                    installedTerrain[command.dimension]?.remove(command.section)
                    pendingTerrainInvalidations.remove(command.dimension to command.section, queued.generation)
                    pendingSuspensions.decrementAndGet()
                    barrierRoom.signalAll()
                }
                is PhysicsCommand.WakeBody -> worlds[command.dimension]?.wake(command.bodyId)
                is PhysicsCommand.SuspendBody -> {
                    worlds[command.dimension]?.suspend(command.bodyId)
                    pendingSuspensions.decrementAndGet()
                    barrierRoom.signalAll()
                }
                is PhysicsCommand.SuspendProp -> {
                    bodies[command.propId]?.takeIf { it.dimension == command.dimension }?.let {
                        worlds[command.dimension]?.suspend(it.bodyId)
                    }
                    pendingSuspensions.decrementAndGet()
                    barrierRoom.signalAll()
                }
                is PhysicsCommand.ResumeBody -> {
                    worlds[command.dimension]?.resume(command.bodyId)
                    bodies.filterValues { it.dimension == command.dimension && it.bodyId == command.bodyId }.keys.forEach(terrainBlocked::remove)
                }
                is PhysicsCommand.SetMotionType -> worlds[command.dimension]
                    ?.setMotionType(command.bodyId, command.motionType)
                is PhysicsCommand.ApplyCentralImpulse -> worlds[command.dimension]
                    ?.applyCentralImpulse(command.bodyId, command.impulse)
                is PhysicsCommand.SetLinearVelocity -> worlds[command.dimension]
                    ?.setLinearVelocity(command.bodyId, command.velocity)
                is PhysicsCommand.SetTransform -> {
                    val world = worlds[command.dimension]
                    val guarded = bodies.entries.firstOrNull { it.value.dimension == command.dimension && it.value.bodyId == command.bodyId }
                    val guard = guarded?.value?.guard
                    val snapshot = world?.snapshot(command.bodyId)
                    val required = if (guard != null && snapshot != null) AssemblyTerrainCoverage.calculate(
                        guard, snapshot.transform, snapshot.linearVelocity, snapshot.angularVelocity, target = command.transform
                    ) else null
                    if (guard == null || required != null && installedTerrain[command.dimension].orEmpty().containsAll(required.sections)) {
                        world?.setTransform(command.bodyId, command.transform)
                    } else if (guarded != null) {
                        world?.suspend(command.bodyId)
                        if (terrainBlocked.add(guarded.key)) acknowledgements += PhysicsAcknowledgement.AssemblyTerrainBlocked(guarded.key, command.dimension, command.bodyId)
                    }
                }
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
                    return true
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
        stepBoundary.withLock {
            state.set(PhysicsServiceState.FAILED)
            barrierRoom.signalAll()
        }
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
        stepBoundary.withLock {
            when (state.get()) {
                PhysicsServiceState.NEW -> state.set(PhysicsServiceState.STOPPED)
                PhysicsServiceState.RUNNING -> {
                    state.compareAndSet(PhysicsServiceState.RUNNING, PhysicsServiceState.STOPPING)
                    LockSupport.unpark(thread)
                }
                else -> Unit
            }
            barrierRoom.signalAll()
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

    private data class RestoredRuntimeComponent(
        val dimension: PhysicsDimensionId,
        val bodies: List<ParkBody>,
        val definitions: List<PhysicsBodyDefinition>,
        var active: Boolean = false
    )

    private data class RuntimeBody(val dimension: PhysicsDimensionId, val bodyId: PhysicsBodyId, val guard: AssemblyTerrainGuard?, var staged: Boolean = false)
    private data class QueuedCommand(val command: PhysicsCommand, val generation: Long = 0L)
    private data class TerrainScan(val bodies: List<Pair<UUID, RuntimeBody>>, val first: Int, var next: Int = 0)
    private data class PhysicsWorldTuning(
        val gravityY: Double = -32.0,
        val buoyancy: Float = 1.5f,
        val impactSpeedThreshold: Float = 18f
    )

    private companion object {
        const val MAX_QUEUED_IMPACTS = 1024
        const val MAX_COMMANDS_PER_STEP = 256
        const val MAX_TERRAIN_CHECKS_PER_STEP = 16_384
        const val MAX_TERRAIN_SCAN_RESTARTS = 4
        const val MAX_PENDING_COMMANDS = 1024
        const val MAX_NORMAL_COMMANDS = 960
    }
}

/** Counts consecutive interrupted scans, resetting after a complete scan or a fail-closed park. */
internal class TerrainScanRestarts(private val limit: Int) {
    private var count = 0
    fun invalidated() { count++ }
    fun shouldPark(): Boolean = count >= limit
    fun reset() { count = 0 }
}
