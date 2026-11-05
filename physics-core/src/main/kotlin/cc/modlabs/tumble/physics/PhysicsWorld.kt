// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.box3d.b3BodyId
import cc.modlabs.box3d.b3MeshData
import cc.modlabs.box3d.b3MeshDef
import cc.modlabs.box3d.b3Pos
import cc.modlabs.box3d.b3Quat
import cc.modlabs.box3d.b3ShapeDef
import cc.modlabs.box3d.b3ShapeId
import cc.modlabs.box3d.b3SurfaceMaterial
import cc.modlabs.box3d.b3Vec3
import cc.modlabs.box3d.b3WorldId
import cc.modlabs.box3d.b3WorldTransform
import cc.modlabs.box3d.b3JointId
import cc.modlabs.box3d.b3Transform
import cc.modlabs.box3d.global.Box3D.*
import kotlin.math.max
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer

/**
 * A thread-confined Box3D simulation. This class never starts or owns a thread.
 *
 * Construct, mutate, step, query, and close the world from its owning thread. Immutable snapshots
 * may be passed elsewhere after they have been returned by this class.
 */
class PhysicsWorld(private val config: PhysicsWorldConfig = PhysicsWorldConfig()) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private val nativeWorld = createNativeWorld()
    private val bodies = linkedMapOf<PhysicsBodyId, NativePhysicsBody>()
    private val bodyIdsByNativeKey = mutableMapOf<Long, PhysicsBodyId>()
    private val terrainBlocks = mutableMapOf<PhysicsSectionPosition, MutableMap<PhysicsBlockPosition, List<TerrainBox>>>()
    private val terrainSections = mutableMapOf<PhysicsSectionPosition, StaticSectionBody>()
    private val temporaryBodies = mutableMapOf<PhysicsBodyId, Int>()
    private val welds = mutableListOf<NativeWeld>()
    private val toolJoints = mutableListOf<NativeToolJoint>()
    private val suspensionRoots = mutableSetOf<PhysicsBodyId>()
    private val pendingImpactEvents = mutableListOf<PhysicsImpactEvent>()
    private var latestMovedSnapshots = emptyList<PhysicsBodySnapshot>()
    private var nextBodyId = 1L
    private var closed = false
    private var gravity = config.gravity
    private var buoyancyForceScale: Float? = null

    /** Owning physics thread only. */
    @JvmName("createBody")
    fun createBody(definition: PhysicsBodyDefinition): PhysicsBodyId {
        checkUsable()
        val nativeBody = createNativeBody(definition)
        val id = PhysicsBodyId(nextBodyId++)
        val mass = b3Body_GetMass(nativeBody).takeIf { it > 0f } ?: definition.estimatedMass()
        bodies[id] = NativePhysicsBody(nativeBody, definition.motionType, definition.buoyancy, mass = mass)
        bodyIdsByNativeKey[nativeBody.key()] = id
        if (definition.emitImpactEvents) b3Body_EnableHitEvents(nativeBody, true)
        return id
    }

    /** Owning physics thread only. Returns false when the body is already absent. */
    @JvmName("destroyBody")
    fun destroyBody(bodyId: PhysicsBodyId): Boolean {
        checkUsable()
        val body = bodies.remove(bodyId) ?: return false
        suspensionRoots.remove(bodyId)
        temporaryBodies.remove(bodyId)
        bodyIdsByNativeKey.remove(body.nativeId.key())
        pendingImpactEvents.removeIf { it.bodyA == bodyId || it.bodyB == bodyId }
        latestMovedSnapshots = latestMovedSnapshots.filterNot { it.bodyId == bodyId }
        destroyWelds(bodyId)
        destroyToolConstraints(bodyId)
        refreshSuspensions()
        b3DestroyBody(body.nativeId)
        body.nativeId.close()
        return true
    }

    /** Owning physics thread only. Welds two bodies without changing their current relative transform. */
    @JvmName("createWeld")
    fun createWeld(bodyA: PhysicsBodyId, bodyB: PhysicsBodyId): Boolean {
        checkUsable()
        if (bodyA == bodyB || welds.any { it.connects(bodyA, bodyB) }) return false
        val first = bodies[bodyA] ?: return false
        val second = bodies[bodyB] ?: return false
        if (first.suspended != null || second.suspended != null) return false
        val transformA = snapshotOf(bodyA, first).transform
        val transformB = snapshotOf(bodyB, second).transform
        val anchor = (transformA.position + transformB.position) * 0.5
        val frameA = localJointFrame(transformA, anchor, transformA.rotation)
        val frameB = localJointFrame(transformB, anchor, transformA.rotation)
        val definition = b3DefaultWeldJointDef()
        return try {
            definition.base()
                .bodyIdA(first.nativeId)
                .bodyIdB(second.nativeId)
                .localFrameA(frameA)
                .localFrameB(frameB)
                .collideConnected(false)
            val joint = b3CreateWeldJoint(nativeWorld, definition)
            if (!b3Joint_IsValid(joint)) {
                joint.close()
                false
            } else {
                welds += NativeWeld(joint, bodyA, bodyB)
                true
            }
        } finally {
            definition.close()
            frameB.close()
            frameA.close()
        }
    }

    /** Owning physics thread only. */
    @JvmName("destroyWelds")
    fun destroyWelds(bodyId: PhysicsBodyId): Int {
        checkUsable()
        val matches = welds.filter { it.bodyA == bodyId || it.bodyB == bodyId }
        matches.forEach { weld ->
            b3DestroyJoint(weld.nativeId, true)
            weld.nativeId.close()
        }
        welds.removeAll(matches.toSet())
        if (matches.isNotEmpty()) refreshSuspensions()
        return matches.size
    }

    /** Owning physics thread only. */
    @JvmName("destroyWeld")
    fun destroyWeld(bodyA: PhysicsBodyId, bodyB: PhysicsBodyId): Boolean {
        checkUsable()
        val weld = welds.firstOrNull { it.connects(bodyA, bodyB) } ?: return false
        b3DestroyJoint(weld.nativeId, true)
        weld.nativeId.close()
        welds.remove(weld)
        refreshSuspensions()
        return true
    }

    fun weldCount(): Int {
        checkUsable()
        return welds.size
    }

    @JvmName("createRope")
    fun createRope(bodyA: PhysicsBodyId, bodyB: PhysicsBodyId, length: Float): Boolean {
        checkUsable()
        require(length.isFinite() && length > 0f) { "Rope length must be positive" }
        val first = bodies[bodyA] ?: return false
        val second = bodies[bodyB] ?: return false
        if (first.suspended != null || second.suspended != null) return false
        val transformA = snapshotOf(bodyA, first).transform
        val transformB = snapshotOf(bodyB, second).transform
        val frameA = localJointFrame(transformA, transformA.position, transformA.rotation)
        val frameB = localJointFrame(transformB, transformB.position, transformB.rotation)
        val definition = b3DefaultDistanceJointDef()
        return try {
            definition.base().bodyIdA(first.nativeId).bodyIdB(second.nativeId)
                .localFrameA(frameA).localFrameB(frameB).collideConnected(false)
            definition.length(length).enableSpring(true).hertz(2f).dampingRatio(0.7f)
            trackToolJoint(b3CreateDistanceJoint(nativeWorld, definition), bodyA, bodyB)
        } finally {
            definition.close()
            frameB.close()
            frameA.close()
        }
    }

    @JvmName("createAxis")
    fun createAxis(bodyA: PhysicsBodyId, bodyB: PhysicsBodyId): Boolean {
        checkUsable()
        val first = bodies[bodyA] ?: return false
        val second = bodies[bodyB] ?: return false
        if (first.suspended != null || second.suspended != null) return false
        val transformA = snapshotOf(bodyA, first).transform
        val transformB = snapshotOf(bodyB, second).transform
        val anchor = (transformA.position + transformB.position) * 0.5
        val frameA = localJointFrame(transformA, anchor, transformA.rotation)
        val frameB = localJointFrame(transformB, anchor, transformA.rotation)
        val definition = b3DefaultRevoluteJointDef()
        return try {
            definition.base().bodyIdA(first.nativeId).bodyIdB(second.nativeId)
                .localFrameA(frameA).localFrameB(frameB).collideConnected(false)
            trackToolJoint(b3CreateRevoluteJoint(nativeWorld, definition), bodyA, bodyB)
        } finally {
            definition.close()
            frameB.close()
            frameA.close()
        }
    }

    @JvmName("createNoCollide")
    fun createNoCollide(bodyA: PhysicsBodyId, bodyB: PhysicsBodyId): Boolean {
        checkUsable()
        val first = bodies[bodyA] ?: return false
        val second = bodies[bodyB] ?: return false
        if (first.suspended != null || second.suspended != null) return false
        val definition = b3DefaultFilterJointDef()
        return try {
            definition.base().bodyIdA(first.nativeId).bodyIdB(second.nativeId).collideConnected(false)
            trackToolJoint(b3CreateFilterJoint(nativeWorld, definition), bodyA, bodyB)
        } finally {
            definition.close()
        }
    }

    @JvmName("destroyToolConstraints")
    fun destroyToolConstraints(bodyId: PhysicsBodyId): Int {
        checkUsable()
        val matches = toolJoints.filter { it.bodyA == bodyId || it.bodyB == bodyId }
        matches.forEach {
            b3DestroyJoint(it.nativeId, true)
            it.nativeId.close()
        }
        toolJoints.removeAll(matches.toSet())
        if (matches.isNotEmpty()) refreshSuspensions()
        return matches.size
    }

    private fun trackToolJoint(joint: b3JointId, bodyA: PhysicsBodyId, bodyB: PhysicsBodyId): Boolean {
        if (!b3Joint_IsValid(joint)) {
            joint.close()
            return false
        }
        toolJoints += NativeToolJoint(joint, bodyA, bodyB)
        return true
    }

    @JvmName("containsBody")
    fun containsBody(bodyId: PhysicsBodyId): Boolean {
        checkUsable()
        return bodyId in bodies
    }

    @JvmName("snapshot")
    fun snapshot(bodyId: PhysicsBodyId): PhysicsBodySnapshot? {
        checkUsable()
        return bodies[bodyId]?.let { snapshotOf(bodyId, it) }
    }

    fun snapshots(): List<PhysicsBodySnapshot> {
        checkUsable()
        return bodies.map { (id, body) -> snapshotOf(id, body) }
    }

    fun movedSnapshots(): List<PhysicsBodySnapshot> {
        checkUsable()
        return latestMovedSnapshots
    }

    /** Owning physics thread only. Advances exactly one configured fixed step. */
    fun step(): List<PhysicsBodySnapshot> {
        checkUsable()
        bodies.values.forEach(::applyWaterResponse)
        b3World_Step(nativeWorld, config.stepSeconds, config.substeps)
        captureImpactEvents()
        latestMovedSnapshots = captureMovedSnapshots()

        temporaryBodies.entries.toList().forEach { (bodyId, remaining) ->
            if (remaining <= 1) destroyBody(bodyId) else temporaryBodies[bodyId] = remaining - 1
        }
        return latestMovedSnapshots
    }

    /** Advances once and reuses move-event snapshots when collecting all bodies. */
    fun stepAndSnapshots(): List<PhysicsBodySnapshot> {
        val moved = step().associateBy { it.bodyId }
        return bodies.map { (id, body) -> moved[id] ?: snapshotOf(id, body) }
    }

    /** Owning physics thread only. */
    @JvmName("setLinearVelocity")
    fun setLinearVelocity(bodyId: PhysicsBodyId, velocity: PhysicsVector): Boolean = withActiveBody(bodyId) { body ->
        velocity.toNativeVector().use { nativeVelocity -> b3Body_SetLinearVelocity(body.nativeId, nativeVelocity) }
        b3Body_SetAwake(body.nativeId, true)
    }

    /** Owning physics thread only. */
    @JvmName("setTransform")
    fun setTransform(bodyId: PhysicsBodyId, transform: PhysicsTransform): Boolean = withActiveBody(bodyId) { body ->
        transform.position.toNativePosition().use { position ->
            transform.rotation.normalized().toNativeRotation().use { rotation ->
                b3Body_SetTransform(body.nativeId, position, rotation)
            }
        }
        b3Body_SetAwake(body.nativeId, true)
    }

    /** Owning physics thread only. */
    @JvmName("addLinearVelocity")
    fun addLinearVelocity(bodyId: PhysicsBodyId, delta: PhysicsVector): Boolean = withActiveBody(bodyId) { body ->
        b3Body_GetLinearVelocity(body.nativeId).let { current ->
            try {
                b3Vec3().x(current.x() + delta.x.toFloat())
                    .y(current.y() + delta.y.toFloat())
                    .z(current.z() + delta.z.toFloat())
                    .use { b3Body_SetLinearVelocity(body.nativeId, it) }
            } finally {
                current.close()
            }
        }
        b3Body_SetAwake(body.nativeId, true)
    }

    /** Owning physics thread only. */
    @JvmName("setAngularVelocity")
    fun setAngularVelocity(bodyId: PhysicsBodyId, velocity: PhysicsVector): Boolean = withActiveBody(bodyId) { body ->
        velocity.toNativeVector().use { nativeVelocity -> b3Body_SetAngularVelocity(body.nativeId, nativeVelocity) }
        b3Body_SetAwake(body.nativeId, true)
    }

    /** Owning physics thread only. */
    @JvmName("applyCentralForce")
    fun applyCentralForce(bodyId: PhysicsBodyId, force: PhysicsVector): Boolean = withActiveBody(bodyId) { body ->
        force.toNativeVector().use { nativeForce -> b3Body_ApplyForceToCenter(body.nativeId, nativeForce, true) }
    }

    /** Owning physics thread only. */
    @JvmName("applyForceAtPosition")
    fun applyForceAtPosition(
        bodyId: PhysicsBodyId,
        force: PhysicsVector,
        worldPosition: PhysicsVector
    ): Boolean = withActiveBody(bodyId) { body ->
        force.toNativeVector().use { nativeForce ->
            worldPosition.toNativePosition().use { nativePosition ->
                b3Body_ApplyForce(body.nativeId, nativeForce, nativePosition, true)
            }
        }
    }

    /** Owning physics thread only. */
    @JvmName("applyCentralImpulse")
    fun applyCentralImpulse(bodyId: PhysicsBodyId, impulse: PhysicsVector): Boolean = withActiveBody(bodyId) { body ->
        impulse.toNativeVector().use { nativeImpulse ->
            b3Body_ApplyLinearImpulseToCenter(body.nativeId, nativeImpulse, true)
        }
    }

    /** Owning physics thread only. */
    @JvmName("wake")
    fun wake(bodyId: PhysicsBodyId): Boolean = withBody(bodyId) {
        b3Body_SetAwake(it.nativeId, true)
    }

    @JvmName("isSleeping")
    fun isSleeping(bodyId: PhysicsBodyId): Boolean? {
        checkUsable()
        return bodies[bodyId]?.let { !b3Body_IsAwake(it.nativeId) }
    }

    /** Owning physics thread only. */
    @JvmName("setMotionType")
    fun setMotionType(bodyId: PhysicsBodyId, motionType: PhysicsMotionType): Boolean = withBody(bodyId) {
        if (it.suspended != null) {
            it.suspended = it.suspended!!.copy(motionType = motionType)
            it.motionType = motionType
            return@withBody
        }
        b3Body_SetType(it.nativeId, motionType.nativeValue)
        it.motionType = motionType
        if (motionType == PhysicsMotionType.DYNAMIC) b3Body_SetAwake(it.nativeId, true)
    }

    /** Temporarily holds a connected component in place while preserving its motion. */
    @JvmName("suspend")
    fun suspend(bodyId: PhysicsBodyId): Boolean {
        checkUsable()
        if (bodyId !in bodies) return false
        suspensionRoots.add(bodyId)
        refreshSuspensions()
        return true
    }

    /** Removes a suspension reason; linked bodies resume when no reason remains. */
    @JvmName("resume")
    fun resume(bodyId: PhysicsBodyId): Boolean {
        checkUsable()
        if (bodyId !in bodies) return false
        suspensionRoots.remove(bodyId)
        refreshSuspensions()
        return true
    }

    private fun refreshSuspensions() {
        val paused = suspensionRoots.toMutableSet()
        val queue = ArrayDeque(suspensionRoots)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            welds.forEach { joint ->
                val other = if (joint.bodyA == id) joint.bodyB else if (joint.bodyB == id) joint.bodyA else null
                if (other != null && paused.add(other)) queue.addLast(other)
            }
            toolJoints.forEach { joint ->
                val other = if (joint.bodyA == id) joint.bodyB else if (joint.bodyB == id) joint.bodyA else null
                if (other != null && paused.add(other)) queue.addLast(other)
            }
        }
        bodies.forEach { (id, body) ->
            val suspended = body.suspended
            if (id in paused && suspended == null) {
                val snapshot = snapshotOf(id, body)
                body.suspended = SuspendedMotion(body.motionType, snapshot.linearVelocity, snapshot.angularVelocity)
                b3Body_SetType(body.nativeId, PhysicsMotionType.STATIC.nativeValue)
            } else if (id !in paused && suspended != null) {
                b3Body_SetType(body.nativeId, suspended.motionType.nativeValue)
                if (suspended.motionType != PhysicsMotionType.STATIC) {
                    suspended.linearVelocity.toNativeVector().use { b3Body_SetLinearVelocity(body.nativeId, it) }
                    suspended.angularVelocity.toNativeVector().use { b3Body_SetAngularVelocity(body.nativeId, it) }
                    b3Body_SetAwake(body.nativeId, true)
                }
                body.motionType = suspended.motionType
                body.suspended = null
            }
        }
    }

    /** Owning physics thread only. The body must be kinematic. */
    @JvmName("setKinematicTarget")
    fun setKinematicTarget(
        bodyId: PhysicsBodyId,
        target: PhysicsTransform,
        travelSeconds: Float
    ): Boolean {
        require(travelSeconds.isFinite() && travelSeconds > 0f) { "Kinematic travel time must be positive" }
        return withActiveBody(bodyId) { body ->
            require(body.motionType == PhysicsMotionType.KINEMATIC) { "Body $bodyId is not kinematic" }
            target.toNativeTransform().use { b3Body_SetTargetTransform(body.nativeId, it, travelSeconds, true) }
        }
    }

    /** Owning physics thread only. Creates a kinematic body that destroys itself after lifetimeSteps. */
    @JvmName("createTemporaryKinematicBody")
    fun createTemporaryKinematicBody(
        definition: PhysicsBodyDefinition,
        targetTransform: PhysicsTransform,
        travelSeconds: Float,
        lifetimeSteps: Int
    ): PhysicsBodyId {
        require(lifetimeSteps > 0) { "Temporary body lifetime must be positive" }
        val bodyId = createBody(definition.copy(motionType = PhysicsMotionType.KINEMATIC))
        try {
            setKinematicTarget(bodyId, targetTransform, travelSeconds)
            temporaryBodies[bodyId] = lifetimeSteps
            return bodyId
        } catch (error: Throwable) {
            destroyBody(bodyId)
            throw error
        }
    }

    /** Owning physics thread only. Returns false for an unknown body. */
    @JvmName("setSubmersion")
    fun setSubmersion(bodyId: PhysicsBodyId, fraction: Float): Boolean {
        require(fraction.isFinite() && fraction in 0f..1f) { "Submersion must be between 0 and 1" }
        return withBody(bodyId) {
            val changed = it.submersion != fraction
            it.submersion = fraction
            if (changed && it.buoyancy.enabled) b3Body_SetAwake(it.nativeId, true)
        }
    }

    fun setGravity(value: PhysicsVector) {
        checkUsable()
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) { "Gravity must be finite" }
        value.toNativeVector().use { b3World_SetGravity(nativeWorld, it) }
        gravity = value
        wakeDynamicBodies()
    }

    fun setBuoyancyForceScale(value: Float) {
        checkUsable()
        require(value.isFinite() && value >= 0f) { "Buoyancy force scale must be non-negative" }
        buoyancyForceScale = value
        wakeDynamicBodies()
    }

    fun setImpactSpeedThreshold(value: Float) {
        checkUsable()
        require(value.isFinite() && value >= 0f) { "Impact speed threshold must be non-negative" }
        b3World_SetHitEventThreshold(nativeWorld, value)
    }

    /** Owning physics thread only. Returns and clears accumulated significant impacts. */
    fun consumeImpactEvents(): List<PhysicsImpactEvent> {
        checkUsable()
        if (pendingImpactEvents.isEmpty()) return emptyList()
        return pendingImpactEvents.toList().also { pendingImpactEvents.clear() }
    }

    /** Owning physics thread only. Rebuilds only sections touched by changed block geometry. */
    fun syncTerrain(updates: Collection<TerrainBlockGeometry>): Set<PhysicsSectionPosition> {
        checkUsable()
        val staged = linkedMapOf<PhysicsSectionPosition, MutableMap<PhysicsBlockPosition, List<TerrainBox>>>()
        updates.forEach { update ->
            val section = update.position.section()
            val blocks = staged.getOrPut(section) { terrainBlocks[section]?.toMutableMap() ?: linkedMapOf() }
            if (update.boxes.isEmpty()) blocks.remove(update.position) else blocks[update.position] = update.boxes
        }
        val dirty = staged.filter { (section, blocks) -> blocks != terrainBlocks[section].orEmpty() }
        if (dirty.isEmpty()) return emptySet()
        val replacements = linkedMapOf<PhysicsSectionPosition, StaticSectionBody?>()
        try {
            dirty.forEach { (section, blocks) ->
                val boxes = blocks.values.flatten()
                replacements[section] = if (boxes.isEmpty()) null else createTerrainSection(section, boxes)
            }
        } catch (error: Throwable) {
            replacements.values.filterNotNull().forEach(StaticSectionBody::destroy)
            throw error
        }
        dirty.forEach { (section, blocks) ->
            if (blocks.isEmpty()) terrainBlocks.remove(section) else terrainBlocks[section] = blocks
            val replacement = replacements[section]
            val previous = if (replacement == null) terrainSections.remove(section)
                else terrainSections.put(section, replacement)
            previous?.destroy()
        }
        wakeDynamicBodies()
        return dirty.keys
    }

    /** Owning physics thread only. Replaces all geometry without dropping valid colliders on failure. */
    fun replaceTerrain(geometry: Collection<TerrainBlockGeometry>) {
        checkUsable()
        val incoming = geometry.mapTo(hashSetOf()) { it.position }
        val removals = terrainBlocks.values.flatMap { it.keys }
            .filterNot(incoming::contains).map { TerrainBlockGeometry(it, emptyList()) }
        syncTerrain(removals + geometry)
    }

    /** Owning physics thread only. Atomically replaces the contents of one section. */
    fun replaceTerrainSection(section: PhysicsSectionPosition, geometry: Collection<TerrainBlockGeometry>) {
        checkUsable()
        require(geometry.all { it.position.section() == section }) { "Geometry must belong to the requested section" }
        val incoming = geometry.mapTo(hashSetOf()) { it.position }
        val removals = terrainBlocks[section].orEmpty().keys.filterNot(incoming::contains)
            .map { TerrainBlockGeometry(it, emptyList()) }
        syncTerrain(removals + geometry)
    }

    /** Owning physics thread only. */
    fun removeTerrainSection(position: PhysicsSectionPosition): Boolean {
        checkUsable()
        terrainBlocks.remove(position)
        val removed = terrainSections.remove(position) ?: return false
        removed.destroy()
        wakeDynamicBodies()
        return true
    }

    /** Owning physics thread only. */
    fun clearTerrain() {
        checkUsable()
        clearTerrainInternal()
        wakeDynamicBodies()
    }

    internal fun terrainSectionCount(): Int {
        checkUsable()
        return terrainSections.size
    }

    internal fun terrainTriangleCount(position: PhysicsSectionPosition): Int? {
        checkUsable()
        return terrainSections[position]?.triangleCount
    }

    internal fun hullVertexCounts(bodyId: PhysicsBodyId): List<Int> {
        checkUsable()
        val body = bodies[bodyId] ?: return emptyList()
        val count = b3Body_GetShapeCount(body.nativeId)
        if (count == 0) return emptyList()
        val shapes = b3ShapeId(count.toLong())
        return try {
            val actual = b3Body_GetShapes(body.nativeId, shapes, count)
            (0 until actual).map { b3Shape_GetHull(shapes.position(it.toLong())).vertexCount() }
        } finally {
            shapes.close()
        }
    }

    override fun close() {
        checkOwnerThread()
        if (closed) return
        clearTerrainInternal()
        b3DestroyWorld(nativeWorld)
        nativeWorld.close()
        welds.forEach { it.nativeId.close() }
        welds.clear()
        toolJoints.forEach { it.nativeId.close() }
        toolJoints.clear()
        bodies.values.forEach { it.nativeId.close() }
        bodies.clear()
        bodyIdsByNativeKey.clear()
        temporaryBodies.clear()
        pendingImpactEvents.clear()
        latestMovedSnapshots = emptyList()
        closed = true
    }

    private fun createNativeWorld(): b3WorldId {
        check(b3IsDoublePrecision()) { "Tumble requires a double-precision Box3D native library" }
        require(config.workerCount <= B3_MAX_WORKERS) { "Worker count must not exceed $B3_MAX_WORKERS" }
        val definition = b3DefaultWorldDef()
        val gravity = config.gravity.toNativeVector()
        return try {
            definition.workerCount(config.workerCount)
            definition.gravity(gravity)
            definition.enableContinuous(true)
            b3CreateWorld(definition).also {
                check(b3World_IsValid(it)) { "Box3D failed to create a physics world" }
                b3World_SetHitEventThreshold(it, config.impactSpeedThreshold)
            }
        } finally {
            gravity.close()
            definition.close()
        }
    }

    private fun createNativeBody(definition: PhysicsBodyDefinition): b3BodyId {
        val bodyDefinition = b3DefaultBodyDef()
        val position = definition.transform.position.toNativePosition()
        val rotation = definition.transform.rotation.normalized().toNativeRotation()
        val linearVelocity = definition.linearVelocity.toNativeVector()
        val angularVelocity = definition.angularVelocity.toNativeVector()
        val nativeBody = try {
            bodyDefinition.type(definition.motionType.nativeValue)
            bodyDefinition._position(position)
            bodyDefinition.rotation(rotation)
            bodyDefinition.linearVelocity(linearVelocity)
            bodyDefinition.angularVelocity(angularVelocity)
            b3CreateBody(nativeWorld, bodyDefinition)
        } finally {
            angularVelocity.close()
            linearVelocity.close()
            rotation.close()
            position.close()
            bodyDefinition.close()
        }
        check(b3Body_IsValid(nativeBody)) { "Box3D failed to create a physics body" }
        try {
            createBodyShapes(nativeBody, definition)
            return nativeBody
        } catch (error: Throwable) {
            b3DestroyBody(nativeBody)
            nativeBody.close()
            throw error
        }
    }

    private fun createBodyShapes(nativeBody: b3BodyId, definition: PhysicsBodyDefinition) {
        val material = definition.material.toNativeMaterial()
        val shapeDefinition = b3DefaultShapeDef()
        try {
            shapeDefinition.updateBodyMass(false)
            shapeDefinition.baseMaterial(material)
            shapeDefinition.density(definition.material.density)
            when (val shape = definition.shape) {
                is PhysicsShapeDefinition.Box -> createExactBoxShape(nativeBody, shapeDefinition, shape.box)
                is PhysicsShapeDefinition.CompoundBoxes -> shape.boxes.forEach { createExactBoxShape(nativeBody, shapeDefinition, it) }
                is PhysicsShapeDefinition.MaterialBoxes -> shape.boxes.forEach { part ->
                    material.copyFrom(part.material)
                    shapeDefinition.baseMaterial(material)
                    shapeDefinition.density(part.material.density)
                    createExactBoxShape(nativeBody, shapeDefinition, part.box)
                }
            }
            b3Body_ApplyMassFromShapes(nativeBody)
        } finally {
            shapeDefinition.close()
            material.close()
        }
    }

    private fun createExactBoxShape(body: b3BodyId, definition: b3ShapeDef, box: PhysicsBox) {
        val half = box.halfExtents
        box.center.toNativeVector().use { center ->
            b3MakeOffsetBoxHull(half.x.toFloat(), half.y.toFloat(), half.z.toFloat(), center).use { hull ->
                b3CreateHullShape(body, definition, hull.base())
            }
        }
    }

    private fun applyWaterResponse(body: NativePhysicsBody) {
        val buoyancy = body.buoyancy
        val fraction = body.submersion.coerceIn(0f, 1f)
        if (!buoyancy.enabled || fraction <= 0f || !b3Body_IsAwake(body.nativeId)) return

        val strength = (buoyancyForceScale ?: 1f) * buoyancy.forceScale
        val upwardForce = b3Body_GetMass(body.nativeId) * max(0.0, -gravity.y).toFloat() *
            buoyancyResponse(fraction, strength)
        PhysicsVector(0.0, upwardForce.toDouble(), 0.0).toNativeVector().use {
            b3Body_ApplyForceToCenter(body.nativeId, it, false)
        }

        val linearScale = 1f - buoyancy.linearDampingPerStep * fraction
        b3Body_GetLinearVelocity(body.nativeId).let { velocity ->
            try {
                b3Vec3().x(velocity.x() * linearScale).y(velocity.y() * linearScale)
                    .z(velocity.z() * linearScale).use { b3Body_SetLinearVelocity(body.nativeId, it) }
            } finally {
                velocity.close()
            }
        }
        val angularScale = 1f - buoyancy.angularDampingPerStep * fraction
        b3Body_GetAngularVelocity(body.nativeId).let { velocity ->
            try {
                val correction = waterLevelingCorrection(body.nativeId, buoyancy.levelingPerStep * fraction)
                b3Vec3().x(velocity.x() * angularScale + correction.x.toFloat())
                    .y(velocity.y() * angularScale + correction.y.toFloat())
                    .z(velocity.z() * angularScale + correction.z.toFloat())
                    .use { b3Body_SetAngularVelocity(body.nativeId, it) }
            } finally {
                velocity.close()
            }
        }
    }

    private fun waterLevelingCorrection(bodyId: b3BodyId, strength: Float): PhysicsVector {
        if (strength <= 0f) return PhysicsVector.ZERO
        val transform = b3Body_GetTransform(bodyId)
        return try {
            val rotation = transform.toPhysicsTransform().rotation
            val upwardFace = listOf(
                rotation.rotate(PhysicsVector(1.0, 0.0, 0.0)),
                rotation.rotate(PhysicsVector(0.0, 1.0, 0.0)),
                rotation.rotate(PhysicsVector(0.0, 0.0, 1.0))
            ).map { if (it.y < 0.0) it * -1.0 else it }.maxBy(PhysicsVector::y)
            upwardFace.cross(PhysicsVector(0.0, 1.0, 0.0)) * strength.toDouble()
        } finally {
            transform.close()
        }
    }

    private fun captureMovedSnapshots(): List<PhysicsBodySnapshot> {
        val events = b3World_GetBodyEvents(nativeWorld)
        val moves = events.moveEvents()
        return buildList(events.moveCount()) {
            for (index in 0 until events.moveCount()) {
                val nativeId = moves.position(index.toLong()).bodyId()
                val bodyId = bodyIdsByNativeKey[nativeId.key()] ?: continue
                val body = bodies[bodyId] ?: continue
                add(snapshotOf(bodyId, body))
            }
        }
    }

    private fun captureImpactEvents() {
        val events = b3World_GetContactEvents(nativeWorld)
        val hits = events.hitEvents()
        for (index in 0 until events.hitCount()) {
            val hit = hits.position(index.toLong())
            val nativeA = b3Shape_GetBody(hit.shapeIdA())
            val nativeB = b3Shape_GetBody(hit.shapeIdB())
            val bodyA = bodyIdsByNativeKey[nativeA.key()]
            val bodyB = bodyIdsByNativeKey[nativeB.key()]
            if (bodyA == null && bodyB == null) continue
            pendingImpactEvents += PhysicsImpactEvent(
                bodyA,
                bodyB,
                hit.point().toPhysicsVector(),
                hit.normal().toPhysicsVector(),
                hit.approachSpeed()
            )
        }
    }

    private fun snapshotOf(bodyId: PhysicsBodyId, body: NativePhysicsBody): PhysicsBodySnapshot {
        val nativeId = body.nativeId
        val transform = b3Body_GetTransform(nativeId)
        val linearVelocity = b3Body_GetLinearVelocity(nativeId)
        val angularVelocity = b3Body_GetAngularVelocity(nativeId)
        return try {
            PhysicsBodySnapshot(
                bodyId,
                transform.toPhysicsTransform(),
                body.suspended?.linearVelocity ?: linearVelocity.toPhysicsVector(),
                body.suspended?.angularVelocity ?: angularVelocity.toPhysicsVector(),
                !b3Body_IsAwake(nativeId),
                body.mass,
                body.suspended != null
            )
        } finally {
            angularVelocity.close()
            linearVelocity.close()
            transform.close()
        }
    }

    private fun createTerrainSection(section: PhysicsSectionPosition, boxes: List<TerrainBox>): StaticSectionBody? {
        val origin = PhysicsVector(section.x * 16.0, section.y * 16.0, section.z * 16.0)
        val faces = linkedMapOf<FaceKey, MeshFace>()
        boxes.forEach { terrainBox ->
            terrainBox.faces().forEach { face ->
                if (faces.remove(face.key) == null) faces[face.key] = face
            }
        }
        if (faces.isEmpty()) return null
        val mergedFaces = mergeTerrainFaces(faces.values.toList())
        val materials = mergedFaces.map(MeshFace::material).distinct()
        require(materials.size <= 256) { "Terrain section contains too many surface materials" }

        val vertices = b3Vec3(mergedFaces.size * 4L)
        val indices = IntPointer(mergedFaces.size * 6L)
        val materialIndices = BytePointer(mergedFaces.size * 2L)
        val nativeMaterials = b3SurfaceMaterial(materials.size.toLong())
        var mesh: b3MeshData? = null
        try {
            mergedFaces.forEachIndexed { faceIndex, face ->
                face.vertices.forEachIndexed { vertexIndex, vertex ->
                    vertices.position(faceIndex * 4L + vertexIndex)
                        .x((vertex.x - origin.x).toFloat())
                        .y((vertex.y - origin.y).toFloat()).z((vertex.z - origin.z).toFloat())
                }
                val firstVertex = faceIndex * 4
                val firstIndex = faceIndex * 6L
                intArrayOf(firstVertex, firstVertex + 1, firstVertex + 2, firstVertex, firstVertex + 2, firstVertex + 3)
                    .forEachIndexed { offset, value -> indices.put(firstIndex + offset, value) }
                val materialIndex = materials.indexOf(face.material).toByte()
                materialIndices.put(faceIndex * 2L, materialIndex)
                    .put(faceIndex * 2L + 1, materialIndex)
            }
            materials.forEachIndexed { index, material ->
                nativeMaterials.position(index.toLong()).copyFrom(material)
            }
            val meshDefinition = b3MeshDef()
            try {
                meshDefinition.vertices(vertices.position(0))
                meshDefinition.indices(indices.position(0))
                meshDefinition.materialIndices(materialIndices.position(0))
                meshDefinition.vertexCount(mergedFaces.size * 4)
                meshDefinition.triangleCount(mergedFaces.size * 2)
                meshDefinition.weldTolerance(0.0001f)
                meshDefinition.weldVertices(true)
                meshDefinition.useMedianSplit(true)
                meshDefinition.identifyEdges(true)
                mesh = b3CreateMesh(meshDefinition, IntArray(0), 0)
            } finally {
                meshDefinition.close()
            }
            val createdMesh = checkNotNull(mesh)
            check(!createdMesh.isNull) { "Box3D failed to create a terrain mesh" }

            val bodyDefinition = b3DefaultBodyDef()
            val body = try {
                bodyDefinition._position().x(origin.x).y(origin.y).z(origin.z)
                b3CreateBody(nativeWorld, bodyDefinition)
            } finally {
                bodyDefinition.close()
            }
            try {
                val shapeDefinition = b3DefaultShapeDef()
                val scale = b3Vec3().x(1f).y(1f).z(1f)
                try {
                    shapeDefinition.materials(nativeMaterials.position(0))
                    shapeDefinition.materialCount(materials.size)
                    b3CreateMeshShape(body, shapeDefinition, createdMesh, scale)
                } finally {
                    scale.close()
                    shapeDefinition.close()
                }
                return StaticSectionBody(body, createdMesh, mergedFaces.size * 2)
            } catch (error: Throwable) {
                b3DestroyBody(body)
                body.close()
                throw error
            }
        } catch (error: Throwable) {
            mesh?.takeUnless { it.isNull }?.let {
                b3DestroyMesh(it)
                it.close()
            }
            throw error
        } finally {
            nativeMaterials.close()
            materialIndices.close()
            indices.close()
            vertices.close()
        }
    }

    private fun clearTerrainInternal() {
        terrainSections.values.forEach(StaticSectionBody::destroy)
        terrainSections.clear()
        terrainBlocks.clear()
    }

    private fun wakeDynamicBodies() {
        bodies.values.filter { it.motionType == PhysicsMotionType.DYNAMIC }
            .forEach { b3Body_SetAwake(it.nativeId, true) }
    }

    private inline fun withBody(bodyId: PhysicsBodyId, action: (NativePhysicsBody) -> Unit): Boolean {
        checkUsable()
        val body = bodies[bodyId] ?: return false
        action(body)
        return true
    }

    private inline fun withActiveBody(bodyId: PhysicsBodyId, action: (NativePhysicsBody) -> Unit): Boolean {
        checkUsable()
        val body = bodies[bodyId] ?: return false
        if (body.suspended != null) return false
        action(body)
        return true
    }

    private fun checkUsable() {
        checkOwnerThread()
        check(!closed) { "Physics world is closed" }
    }

    private fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) { "Physics world may only be used by its owning thread" }
    }

    private inner class StaticSectionBody(
        private val body: b3BodyId,
        private val mesh: b3MeshData,
        val triangleCount: Int
    ) {
        fun destroy() {
            b3DestroyBody(body)
            body.close()
            b3DestroyMesh(mesh)
            mesh.close()
        }
    }

    private data class NativePhysicsBody(
        val nativeId: b3BodyId,
        var motionType: PhysicsMotionType,
        val buoyancy: PhysicsBuoyancy,
        val mass: Float,
        var submersion: Float = 0f,
        var suspended: SuspendedMotion? = null
    )

    private data class SuspendedMotion(
        val motionType: PhysicsMotionType,
        val linearVelocity: PhysicsVector,
        val angularVelocity: PhysicsVector
    )

    private data class NativeWeld(
        val nativeId: b3JointId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId
    ) {
        fun connects(first: PhysicsBodyId, second: PhysicsBodyId) =
            bodyA == first && bodyB == second || bodyA == second && bodyB == first
    }

    private data class NativeToolJoint(
        val nativeId: b3JointId,
        val bodyA: PhysicsBodyId,
        val bodyB: PhysicsBodyId
    )

    internal data class MeshVertex(val x: Double, val y: Double, val z: Double)
    internal data class FaceKey(
        val axis: Int,
        val plane: Double,
        val minA: Double,
        val maxA: Double,
        val minB: Double,
        val maxB: Double
    )
    internal data class MeshFace(
        val key: FaceKey,
        val vertices: List<MeshVertex>,
        val material: PhysicsMaterial
    )

    private companion object {
    }
}

private fun PhysicsBodyDefinition.estimatedMass(): Float {
    fun volume(box: PhysicsBox) =
        (box.max.x - box.min.x) * (box.max.y - box.min.y) * (box.max.z - box.min.z)
    val mass = when (val value = shape) {
        is PhysicsShapeDefinition.Box -> volume(value.box) * material.density
        is PhysicsShapeDefinition.CompoundBoxes -> value.boxes.sumOf(::volume) * material.density
        is PhysicsShapeDefinition.MaterialBoxes -> value.boxes.sumOf { volume(it.box) * it.material.density }
    }
    return mass.toFloat()
}

private val PhysicsMotionType.nativeValue: Int
    get() = when (this) {
        PhysicsMotionType.STATIC -> b3_staticBody
        PhysicsMotionType.KINEMATIC -> b3_kinematicBody
        PhysicsMotionType.DYNAMIC -> b3_dynamicBody
    }

private fun b3BodyId.key() = generation().toLong() shl 32 or (index1().toLong() and 0xffffffffL)

private fun PhysicsVector.toNativeVector() = b3Vec3().x(x.toFloat()).y(y.toFloat()).z(z.toFloat())
private fun PhysicsVector.toNativePosition() = b3Pos().x(x).y(y).z(z)

private fun PhysicsRotation.toNativeRotation(): b3Quat {
    val vector = b3Vec3().x(x.toFloat()).y(y.toFloat()).z(z.toFloat())
    return try {
        b3Quat().v(vector).s(w.toFloat())
    } finally {
        vector.close()
    }
}

private fun PhysicsTransform.toNativeTransform(): b3WorldTransform {
    val nativePosition = position.toNativePosition()
    val nativeRotation = rotation.normalized().toNativeRotation()
    return try {
        b3WorldTransform().p(nativePosition).q(nativeRotation)
    } finally {
        nativeRotation.close()
        nativePosition.close()
    }
}

private fun localJointFrame(
    body: PhysicsTransform,
    worldPosition: PhysicsVector,
    worldRotation: PhysicsRotation
): b3Transform {
    val inverse = body.rotation.normalized().let { PhysicsRotation(-it.x, -it.y, -it.z, it.w) }
    val localPosition = inverse.rotate(worldPosition - body.position)
    val localRotation = inverse.multiply(worldRotation.normalized()).normalized()
    val nativePosition = localPosition.toNativeVector()
    val nativeRotation = localRotation.toNativeRotation()
    return try {
        b3Transform().p(nativePosition).q(nativeRotation)
    } finally {
        nativeRotation.close()
        nativePosition.close()
    }
}

private fun PhysicsRotation.multiply(other: PhysicsRotation) = PhysicsRotation(
    w * other.x + x * other.w + y * other.z - z * other.y,
    w * other.y - x * other.z + y * other.w + z * other.x,
    w * other.z + x * other.y - y * other.x + z * other.w,
    w * other.w - x * other.x - y * other.y - z * other.z
)

private fun PhysicsRotation.rotate(vector: PhysicsVector): PhysicsVector {
    val q = normalized()
    val tx = 2.0 * (q.y * vector.z - q.z * vector.y)
    val ty = 2.0 * (q.z * vector.x - q.x * vector.z)
    val tz = 2.0 * (q.x * vector.y - q.y * vector.x)
    return PhysicsVector(
        vector.x + q.w * tx + q.y * tz - q.z * ty,
        vector.y + q.w * ty + q.z * tx - q.x * tz,
        vector.z + q.w * tz + q.x * ty - q.y * tx
    )
}

private fun PhysicsVector.cross(other: PhysicsVector) = PhysicsVector(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x
)

private fun buoyancyResponse(fraction: Float, strength: Float): Float {
    val baselineStrength = minOf(strength, 1f / BUOYANCY_REST_SUBMERSION)
    val baseline = baselineStrength * fraction
    if (strength <= baselineStrength || fraction <= BUOYANCY_REST_SUBMERSION) return baseline
    val load = ((fraction - BUOYANCY_REST_SUBMERSION) / (1f - BUOYANCY_REST_SUBMERSION)).coerceIn(0f, 1f)
    return baseline + (strength - baselineStrength) * load * load
}

private const val BUOYANCY_REST_SUBMERSION = 0.72f

private fun b3Vec3.toPhysicsVector() = PhysicsVector(x().toDouble(), y().toDouble(), z().toDouble())
private fun b3Pos.toPhysicsVector() = PhysicsVector(x(), y(), z())

private fun b3WorldTransform.toPhysicsTransform(): PhysicsTransform {
    val nativeRotation = q()
    val vector = nativeRotation.v()
    return PhysicsTransform(
        p().toPhysicsVector(),
        PhysicsRotation(vector.x().toDouble(), vector.y().toDouble(), vector.z().toDouble(), nativeRotation.s().toDouble())
    )
}

private fun PhysicsMaterial.toNativeMaterial() = b3DefaultSurfaceMaterial().copyFrom(this)

private fun b3SurfaceMaterial.copyFrom(material: PhysicsMaterial): b3SurfaceMaterial {
    val tangent = b3Vec3().x(0f).y(0f).z(0f)
    return try {
        friction(material.friction)
        restitution(material.restitution)
        rollingResistance(material.rollingResistance)
        tangentVelocity(tangent)
        userMaterialId(0)
        customColor(0)
        this
    } finally {
        tangent.close()
    }
}

private data class FaceMergeGroup(
    val axis: Int, val plane: Double, val direction: Boolean, val material: PhysicsMaterial,
    val fixedMin: Double, val fixedMax: Double
)

private fun mergeTerrainFaces(faces: List<PhysicsWorld.MeshFace>): List<PhysicsWorld.MeshFace> {
    fun mergeAlong(input: List<PhysicsWorld.MeshFace>, alongA: Boolean): List<PhysicsWorld.MeshFace> {
        val groups = input.groupBy { face ->
            val key = face.key
            val (a, b, c) = face.vertices
            val ux = b.x - a.x
            val uy = b.y - a.y
            val uz = b.z - a.z
            val vx = c.x - a.x
            val vy = c.y - a.y
            val vz = c.z - a.z
            val normal = when (key.axis) {
                0 -> uy * vz - uz * vy
                1 -> uz * vx - ux * vz
                else -> ux * vy - uy * vx
            }
            FaceMergeGroup(key.axis, key.plane, normal > 0, face.material,
                if (alongA) key.minB else key.minA, if (alongA) key.maxB else key.maxA)
        }
        return groups.values.flatMap { group ->
            val sorted = group.sortedBy { if (alongA) it.key.minA else it.key.minB }
            val result = mutableListOf<PhysicsWorld.MeshFace>()
            for (face in sorted) {
                val previous = result.lastOrNull()
                val touches = previous != null && if (alongA) previous.key.maxA == face.key.minA
                    else previous.key.maxB == face.key.minB
                if (!touches) {
                    result += face
                    continue
                }
                val old = previous
                val oldKey = old.key
                val key = if (alongA) oldKey.copy(maxA = face.key.maxA) else oldKey.copy(maxB = face.key.maxB)
                val vertices = old.vertices.map { vertex ->
                    fun extend(value: Double, oldMax: Double, newMax: Double) = if (value == oldMax) newMax else value
                    when (key.axis) {
                        0 -> vertex.copy(y = extend(vertex.y, oldKey.maxA, key.maxA), z = extend(vertex.z, oldKey.maxB, key.maxB))
                        1 -> vertex.copy(x = extend(vertex.x, oldKey.maxA, key.maxA), z = extend(vertex.z, oldKey.maxB, key.maxB))
                        else -> vertex.copy(x = extend(vertex.x, oldKey.maxA, key.maxA), y = extend(vertex.y, oldKey.maxB, key.maxB))
                    }
                }
                result[result.lastIndex] = old.copy(key = key, vertices = vertices)
            }
            result
        }
    }
    return mergeAlong(mergeAlong(faces, true), false)
}

private fun TerrainBox.faces(): List<PhysicsWorld.MeshFace> {
    fun vertex(x: Double, y: Double, z: Double) = PhysicsWorld.MeshVertex(x, y, z)
    fun face(key: PhysicsWorld.FaceKey, vararg vertices: PhysicsWorld.MeshVertex) =
        PhysicsWorld.MeshFace(key, vertices.toList(), material)
    val min = bounds.min
    val max = bounds.max
    return listOf(
        face(PhysicsWorld.FaceKey(0, min.x, min.y, max.y, min.z, max.z),
            vertex(min.x, min.y, min.z), vertex(min.x, min.y, max.z),
            vertex(min.x, max.y, max.z), vertex(min.x, max.y, min.z)),
        face(PhysicsWorld.FaceKey(0, max.x, min.y, max.y, min.z, max.z),
            vertex(max.x, min.y, min.z), vertex(max.x, max.y, min.z),
            vertex(max.x, max.y, max.z), vertex(max.x, min.y, max.z)),
        face(PhysicsWorld.FaceKey(1, min.y, min.x, max.x, min.z, max.z),
            vertex(min.x, min.y, min.z), vertex(max.x, min.y, min.z),
            vertex(max.x, min.y, max.z), vertex(min.x, min.y, max.z)),
        face(PhysicsWorld.FaceKey(1, max.y, min.x, max.x, min.z, max.z),
            vertex(min.x, max.y, min.z), vertex(min.x, max.y, max.z),
            vertex(max.x, max.y, max.z), vertex(max.x, max.y, min.z)),
        face(PhysicsWorld.FaceKey(2, min.z, min.x, max.x, min.y, max.y),
            vertex(min.x, min.y, min.z), vertex(min.x, max.y, min.z),
            vertex(max.x, max.y, min.z), vertex(max.x, min.y, min.z)),
        face(PhysicsWorld.FaceKey(2, max.z, min.x, max.x, min.y, max.y),
            vertex(min.x, min.y, max.z), vertex(max.x, min.y, max.z),
            vertex(max.x, max.y, max.z), vertex(min.x, max.y, max.z))
    )
}
