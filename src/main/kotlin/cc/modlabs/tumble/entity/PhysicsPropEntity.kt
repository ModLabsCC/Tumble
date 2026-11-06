// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.entity

import cc.modlabs.tumble.physics.PhysicsBodyId
import cc.modlabs.tumble.physics.PhysicsBodySnapshot
import cc.modlabs.tumble.physics.PhysicsAabb
import cc.modlabs.tumble.physics.PhysicsBox
import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.MinecraftTerrainAdapter
import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.ToolGunResult
import cc.modlabs.tumble.assembly.AssemblyStructure
import cc.modlabs.tumble.item.TumbleItems
import com.mojang.serialization.Codec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Quaternionfc

class PhysicsPropEntity(type: EntityType<out PhysicsPropEntity>, level: Level) : Entity(type, level) {
    var assembly: AssemblyStructure? = null
        private set
    val hasAssembly: Boolean get() = entityData.get(DATA_HAS_ASSEMBLY)
    internal var runtimeBodyId: PhysicsBodyId? = null
    internal var physicsPending = false
    internal var recoveryOrigin: BlockPos? = null
    internal var terrainBlockers: List<Long> = emptyList()
    internal var angularVelocity = PhysicsVector.ZERO
    internal var lastAppliedStep = 0L
    private var localBounds = UNIT_LOCAL_BOUNDS
    var localCollisionBoxes: List<PhysicsBox> = listOf(PhysicsBox.UNIT)
        private set

    var actorCollidable: Boolean
        get() = entityData.get(DATA_ACTOR_COLLIDABLE)
        set(value) = entityData.set(DATA_ACTOR_COLLIDABLE, value)

    var blockState: BlockState
        get() = entityData.get(DATA_BLOCK)
        set(value) = entityData.set(DATA_BLOCK, value)

    var mass: Float
        get() = entityData.get(DATA_MASS)
        private set(value) = entityData.set(DATA_MASS, value)

    var frozen: Boolean
        get() = entityData.get(DATA_FROZEN)
        internal set(value) = entityData.set(DATA_FROZEN, value)

    var heldByEntityId: Int
        get() = entityData.get(DATA_HELD_BY)
        internal set(value) = entityData.set(DATA_HELD_BY, value)

    var toolPhysicsPreset = ToolPhysicsPreset.DEFAULT
        internal set

    val bodyRotation: PhysicsRotation
        get() = entityData.get(DATA_ROTATION).let { PhysicsRotation(it.x().toDouble(), it.y().toDouble(), it.z().toDouble(), it.w().toDouble()) }

    init {
        noPhysics = true
        blocksBuilding = false
        setNoGravity(true)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(DATA_BLOCK, Blocks.RAW_COPPER_BLOCK.stateDefinition.any())
        builder.define(DATA_ROTATION, Quaternionf())
        builder.define(DATA_ACTOR_COLLIDABLE, true)
        builder.define(DATA_MASS, 1f)
        builder.define(DATA_FROZEN, false)
        builder.define(DATA_HELD_BY, -1)
        builder.define(DATA_HAS_ASSEMBLY, false)
    }

    fun initialize(block: BlockState, angularVelocity: PhysicsVector) {
        blockState = block
        refreshLocalBounds()
        mass = calculatedMass()
        this.angularVelocity = angularVelocity
    }

    fun initializeAssembly(structure: AssemblyStructure) {
        assembly = structure
        entityData.set(DATA_HAS_ASSEMBLY, true)
        blockState = structure.definition.blocks.first().state
        refreshLocalBounds()
        refreshDimensions()
        mass = calculatedMass()
        angularVelocity = PhysicsVector.ZERO
    }

    internal fun applySnapshot(step: Long, snapshot: PhysicsBodySnapshot) {
        if (step <= lastAppliedStep || isRemoved) return
        lastAppliedStep = step
        val transform = snapshot.transform
        val rotation = transform.rotation.normalized()
        entityData.set(
            DATA_ROTATION,
            Quaternionf(rotation.x.toFloat(), rotation.y.toFloat(), rotation.z.toFloat(), rotation.w.toFloat())
        )
        setPos(transform.position.x, transform.position.y, transform.position.z)
        setDeltaMovement(snapshot.linearVelocity.x, snapshot.linearVelocity.y, snapshot.linearVelocity.z)
        angularVelocity = snapshot.angularVelocity
        mass = snapshot.mass
    }

    internal fun restoreCheckpoint(transform: cc.modlabs.tumble.physics.PhysicsTransform, linear: PhysicsVector, angular: PhysicsVector) {
        val rotation = transform.rotation.normalized()
        entityData.set(DATA_ROTATION, Quaternionf(rotation.x.toFloat(), rotation.y.toFloat(), rotation.z.toFloat(), rotation.w.toFloat()))
        setPos(transform.position.x, transform.position.y, transform.position.z)
        setDeltaMovement(linear.x, linear.y, linear.z)
        angularVelocity = angular
    }

    override fun tick() {
        super.tick()
        fallDistance = 0.0
    }

    override fun makeBoundingBox(position: Vec3): AABB {
        val bounds = PhysicsPropBounds.rotatedAabb(
            runCatching { bodyRotation }.getOrDefault(PhysicsRotation.IDENTITY),
            runCatching { localBounds }.getOrNull() ?: UNIT_LOCAL_BOUNDS
        )
        return AABB(
            position.x + bounds.min.x,
            position.y + bounds.min.y,
            position.z + bounds.min.z,
            position.x + bounds.max.x,
            position.y + bounds.max.y,
            position.z + bounds.max.z
        )
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (accessor == DATA_BLOCK) refreshLocalBounds()
        if (accessor == DATA_ROTATION || accessor == DATA_BLOCK) refreshDimensions()
    }

    override fun isPushable() = false
    override fun canBeCollidedWith(other: Entity?) = false
    override fun isPickable() = true

    override fun remove(reason: RemovalReason) {
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) &&
            !PhysicsServices.beforePropRemoval(this, serverLevel)
        ) return
        try { super.remove(reason) }
        finally { if (serverLevel != null && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) PhysicsServices.afterPropRemoval(this, serverLevel) }
    }

    override fun teleport(transition: net.minecraft.world.level.portal.TeleportTransition): Entity? {
        val serverLevel = level() as? ServerLevel
        if (serverLevel != null && !PhysicsServices.canTeleportProp(this, serverLevel, transition.newLevel())) return null
        return super.teleport(transition)
    }

    private fun passengerRelationshipsBlocked(): Boolean =
        (level() as? ServerLevel)?.let {
            PhysicsServices.assemblyConversionPending(it, uuid) || PhysicsServices.connectionRelationshipsBlocked(it, uuid)
        } == true

    override fun startRiding(entity: Entity, force: Boolean, sendEvent: Boolean): Boolean =
        !passengerRelationshipsBlocked() && super.startRiding(entity, force, sendEvent)

    override fun couldAcceptPassenger(): Boolean = !passengerRelationshipsBlocked() && super.couldAcceptPassenger()

    override fun causeFallDamage(distance: Double, multiplier: Float, source: DamageSource) = false
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        val player = source.entity as? Player ?: return false
        if (player.isHolding { it.item === TumbleItems.TOOL_GUN }) {
            if (PhysicsServices.useToolGunPrimary(player, this)) {
                player.sendOverlayMessage(Component.translatable("message.tumble.tool_gun.primary_applied"))
            }
            return false
        }
        if (!player.isHolding { it.item === TumbleItems.PHYSICS_GUN }) return false
        if (PhysicsServices.usePhysicsGunSecondary(player, this, player.isShiftKeyDown)) {
            player.sendOverlayMessage(
                Component.translatable(
                    if (player.isShiftKeyDown) "message.tumble.physics_gun.block"
                    else "message.tumble.physics_gun.frozen"
                )
            )
        }
        return false
    }

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult {
        if (player.getItemInHand(hand).item === TumbleItems.TOOL_GUN) {
            if (level().isClientSide) return InteractionResult.SUCCESS
            val result = PhysicsServices.useToolGun(player, this)
            player.sendOverlayMessage(Component.translatable(when (result) {
                ToolGunResult.SELECTED -> "message.tumble.tool_gun.selected"
                ToolGunResult.APPLIED -> "message.tumble.tool_gun.applied"
                ToolGunResult.CANCELLED -> "message.tumble.tool_gun.cancelled"
                ToolGunResult.FAILED -> "message.tumble.tool_gun.failed"
            }))
            return if (result == ToolGunResult.FAILED) InteractionResult.FAIL else InteractionResult.SUCCESS_SERVER
        }
        if (player.getItemInHand(hand).item !== TumbleItems.PHYSICS_GUN) return InteractionResult.PASS
        if (level().isClientSide) return InteractionResult.SUCCESS
        val success = if (player.isShiftKeyDown) {
            PhysicsServices.usePhysicsGunSecondary(player, this, true)
        } else {
            PhysicsServices.togglePhysicsGunHeld(player, this)
        }
        return if (success) {
            player.sendOverlayMessage(
                Component.translatable(
                    when {
                        player.isShiftKeyDown -> "message.tumble.physics_gun.block"
                        actorCollidable -> "message.tumble.physics_gun.released"
                        else -> "message.tumble.physics_gun.grabbed"
                    }
                )
            )
            InteractionResult.SUCCESS_SERVER
        } else {
            InteractionResult.FAIL
        }
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        PhysicsPropPersistence(
            BuiltInRegistries.BLOCK.getKey(blockState.block).toString(),
            bodyRotation,
            PhysicsVector(deltaMovement.x, deltaMovement.y, deltaMovement.z),
            angularVelocity,
            frozen
        ).write(output)
        output.store(BLOCK_STATE, BlockState.CODEC, blockState)
        assembly?.let {
            output.putBoolean(HAS_ASSEMBLY, true)
            output.store(ASSEMBLY, AssemblyStructure.CODEC, it)
        }
        recoveryOrigin?.let { output.store(RECOVERY_ORIGIN, BlockPos.CODEC, it) }
        if (terrainBlockers.isNotEmpty()) {
            output.putBoolean(HAS_TERRAIN_BLOCKERS, true)
            output.store(TERRAIN_BLOCKERS, TERRAIN_BLOCKER_CODEC, terrainBlockers)
        }
        output.putFloat(MASS, mass)
        output.putInt(TOOL_PHYSICS_PRESET, toolPhysicsPreset.ordinal)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        val state = PhysicsPropPersistence.read(input)
        blockState = input.read(BLOCK_STATE, BlockState.CODEC).orElseGet {
            BuiltInRegistries.BLOCK.getValue(Identifier.parse(state.blockId)).stateDefinition.any()
        }
        assembly = if (input.getBooleanOr(HAS_ASSEMBLY, false)) {
            input.read(ASSEMBLY, AssemblyStructure.CODEC).orElseThrow { IllegalStateException("Invalid saved assembly") }
        } else null
        recoveryOrigin = input.read(RECOVERY_ORIGIN, BlockPos.CODEC).orElse(null)
        terrainBlockers = if (input.getBooleanOr(HAS_TERRAIN_BLOCKERS, false)) {
            input.read(TERRAIN_BLOCKERS, TERRAIN_BLOCKER_CODEC).orElseThrow { IllegalStateException("Invalid saved terrain blockers") }
        } else emptyList()
        entityData.set(DATA_HAS_ASSEMBLY, assembly != null)
        refreshLocalBounds()
        refreshDimensions()
        entityData.set(
            DATA_ROTATION,
            Quaternionf(
                state.rotation.x.toFloat(),
                state.rotation.y.toFloat(),
                state.rotation.z.toFloat(),
                state.rotation.w.toFloat()
            )
        )
        setDeltaMovement(state.linearVelocity.x, state.linearVelocity.y, state.linearVelocity.z)
        angularVelocity = state.angularVelocity
        frozen = state.frozen
        mass = input.getFloatOr(MASS, calculatedMass())
        toolPhysicsPreset = ToolPhysicsPreset.entries.getOrElse(input.getIntOr(TOOL_PHYSICS_PRESET, 0)) {
            ToolPhysicsPreset.DEFAULT
        }
        runtimeBodyId = null
        physicsPending = false
    }

    companion object {
        private val UNIT_LOCAL_BOUNDS = PhysicsAabb(
            PhysicsVector(-0.5, -0.5, -0.5),
            PhysicsVector(0.5, 0.5, 0.5)
        )
        private val DATA_BLOCK: EntityDataAccessor<BlockState> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.BLOCK_STATE
        )
        private val DATA_ROTATION: EntityDataAccessor<Quaternionfc> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.QUATERNION
        )
        private val DATA_ACTOR_COLLIDABLE: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.BOOLEAN
        )
        private val DATA_MASS: EntityDataAccessor<Float> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.FLOAT
        )
        private val DATA_FROZEN: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.BOOLEAN
        )
        private val DATA_HELD_BY: EntityDataAccessor<Int> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.INT
        )
        private val DATA_HAS_ASSEMBLY: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(
            PhysicsPropEntity::class.java,
            EntityDataSerializers.BOOLEAN
        )
        private const val BLOCK_STATE = "TumbleBlockState"
        private const val HAS_ASSEMBLY = "TumbleHasAssembly"
        private const val ASSEMBLY = "TumbleAssembly"
        private const val RECOVERY_ORIGIN = "TumbleRecoveryOrigin"
        private const val TERRAIN_BLOCKERS = "TumbleTerrainBlockers"
        private const val HAS_TERRAIN_BLOCKERS = "TumbleHasTerrainBlockers"
        private val TERRAIN_BLOCKER_CODEC = Codec.LONG.listOf()
        private const val MASS = "TumbleMass"
        private const val TOOL_PHYSICS_PRESET = "TumbleToolPhysicsPreset"
    }

    private fun refreshLocalBounds() {
        assembly?.let { structure ->
            localCollisionBoxes = structure.shape.boxes.map { it.box }
            localBounds = PhysicsAabb(
                PhysicsVector(localCollisionBoxes.minOf { it.min.x }, localCollisionBoxes.minOf { it.min.y }, localCollisionBoxes.minOf { it.min.z }),
                PhysicsVector(localCollisionBoxes.maxOf { it.max.x }, localCollisionBoxes.maxOf { it.max.y }, localCollisionBoxes.maxOf { it.max.z })
            )
            return
        }
        val boxes = runCatching { blockState.getCollisionShape(level(), blockPosition()).toAabbs() }.getOrDefault(emptyList())
        if (boxes.isEmpty()) {
            localBounds = UNIT_LOCAL_BOUNDS
            localCollisionBoxes = listOf(PhysicsBox.UNIT)
            return
        }
        localCollisionBoxes = boxes.map { box ->
            PhysicsBox(
                PhysicsVector(box.minX - 0.5, box.minY - 0.5, box.minZ - 0.5),
                PhysicsVector(box.maxX - 0.5, box.maxY - 0.5, box.maxZ - 0.5)
            )
        }
        localBounds = PhysicsAabb(
            PhysicsVector(
                boxes.minOf { it.minX } - 0.5,
                boxes.minOf { it.minY } - 0.5,
                boxes.minOf { it.minZ } - 0.5
            ),
            PhysicsVector(
                boxes.maxOf { it.maxX } - 0.5,
                boxes.maxOf { it.maxY } - 0.5,
                boxes.maxOf { it.maxZ } - 0.5
            )
        )
    }

    private fun calculatedMass(): Float {
        assembly?.let { structure ->
            return structure.shape.boxes.sumOf { part ->
                val box = part.box
                (box.max.x - box.min.x) * (box.max.y - box.min.y) * (box.max.z - box.min.z) * part.material.density
            }.toFloat()
        }
        val volume = localCollisionBoxes.sumOf { box ->
            (box.max.x - box.min.x) * (box.max.y - box.min.y) * (box.max.z - box.min.z)
        }
        return (MinecraftTerrainAdapter.material(blockState).density * volume).toFloat()
    }
}

enum class ToolPhysicsPreset(val densityScale: Float, val buoyancyScale: Float) {
    DEFAULT(1f, 1f),
    LIGHT(0.35f, 1.25f),
    FLOATING(0.18f, 2f),
    HEAVY(3f, 0.35f);

    fun next() = entries[(ordinal + 1) % entries.size]
}

object PhysicsPropBounds {
    fun halfExtents(rotation: PhysicsRotation): PhysicsVector {
        val bounds = rotatedAabb(
            rotation,
            PhysicsAabb(PhysicsVector(-0.5, -0.5, -0.5), PhysicsVector(0.5, 0.5, 0.5))
        )
        return PhysicsVector(bounds.max.x, bounds.max.y, bounds.max.z)
    }

    fun rotatedAabb(rotation: PhysicsRotation, local: PhysicsAabb): PhysicsAabb {
        val q = rotation.normalized()
        val xx = q.x * q.x
        val yy = q.y * q.y
        val zz = q.z * q.z
        val xy = q.x * q.y
        val xz = q.x * q.z
        val yz = q.y * q.z
        val wx = q.w * q.x
        val wy = q.w * q.y
        val wz = q.w * q.z
        val m00 = 1.0 - 2.0 * (yy + zz)
        val m01 = 2.0 * (xy - wz)
        val m02 = 2.0 * (xz + wy)
        val m10 = 2.0 * (xy + wz)
        val m11 = 1.0 - 2.0 * (xx + zz)
        val m12 = 2.0 * (yz - wx)
        val m20 = 2.0 * (xz - wy)
        val m21 = 2.0 * (yz + wx)
        val m22 = 1.0 - 2.0 * (xx + yy)
        val center = (local.min + local.max) * 0.5
        val half = (local.max - local.min) * 0.5
        val rotatedCenter = PhysicsVector(
            m00 * center.x + m01 * center.y + m02 * center.z,
            m10 * center.x + m11 * center.y + m12 * center.z,
            m20 * center.x + m21 * center.y + m22 * center.z
        )
        val rotatedHalf = PhysicsVector(
            kotlin.math.abs(m00) * half.x + kotlin.math.abs(m01) * half.y + kotlin.math.abs(m02) * half.z,
            kotlin.math.abs(m10) * half.x + kotlin.math.abs(m11) * half.y + kotlin.math.abs(m12) * half.z,
            kotlin.math.abs(m20) * half.x + kotlin.math.abs(m21) * half.y + kotlin.math.abs(m22) * half.z
        )
        return PhysicsAabb(rotatedCenter - rotatedHalf, rotatedCenter + rotatedHalf)
    }
}
