// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client

import cc.modlabs.tumble.client.physics.ClientPhysicsState
import cc.modlabs.tumble.client.physics.PhysicsPropLighting
import cc.modlabs.tumble.entity.PhysicsPropEntity
import cc.modlabs.tumble.item.TumbleItems
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.CardinalLighting
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Quaternionf
import cc.modlabs.tumble.physics.PhysicsRotation

class PhysicsPropRenderState : EntityRenderState() {
    val movingBlock = MovingBlockRenderState()
    val rotation = Quaternionf()
    var beamStart: Vec3? = null
    var highlightFace: Direction? = null
    var lightX = Int.MIN_VALUE
    var lightY = Int.MIN_VALUE
    var lightZ = Int.MIN_VALUE
    var lightingBase: CardinalLighting? = null
    var lightingRotation: PhysicsRotation? = null
    var rotatedLighting: CardinalLighting? = null
}

class PhysicsPropRenderer(context: EntityRendererProvider.Context) :
    EntityRenderer<PhysicsPropEntity, PhysicsPropRenderState>(context) {

    init {
        shadowRadius = 0.5f
    }

    override fun createRenderState() = PhysicsPropRenderState()

    override fun extractRenderState(entity: PhysicsPropEntity, state: PhysicsPropRenderState, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        val networkTransform = ClientPhysicsState.sample(entity.uuid)
        val renderPosition = networkTransform?.position
        if (renderPosition != null) {
            state.x = renderPosition.x
            state.y = renderPosition.y
            state.z = renderPosition.z
        }
        // Vanilla falling blocks sample at their top so ambient occlusion is not
        // evaluated from inside the terrain supporting the moving block.
        val lightX = Mth.floor(renderPosition?.x ?: entity.x)
        val lightY = Mth.floor((renderPosition?.y ?: entity.y) + (entity.boundingBox.maxY - entity.y))
        val lightZ = Mth.floor(renderPosition?.z ?: entity.z)
        val rotation = networkTransform?.rotation ?: entity.bodyRotation
        state.rotation.set(
            rotation.x.toFloat(),
            rotation.y.toFloat(),
            rotation.z.toFloat(),
            rotation.w.toFloat()
        ).normalize()
        state.movingBlock.randomSeedPos = BlockPos.ZERO
        state.movingBlock.blockState = entity.blockState
        state.highlightFace = ClientToolGunState.selectedFace.takeIf {
            ClientToolGunState.selectedPropId == entity.uuid
        }
        state.beamStart = entity.level().getEntity(entity.heldByEntityId)
            ?.takeIf { it is Player && it.isHolding { stack -> stack.item === TumbleItems.PHYSICS_GUN } }
            ?.let { holder ->
            val start = holder.getEyePosition(partialTick)
                .add(holder.getViewVector(partialTick).scale(0.42))
                .add(0.0, -0.18, 0.0)
            start.subtract(state.x, state.y, state.z)
        }
        (entity.level() as? ClientLevel)?.let { level ->
            if (state.lightX != lightX || state.lightY != lightY || state.lightZ != lightZ) {
                val lightPosition = BlockPos(lightX, lightY, lightZ)
                state.lightX = lightX
                state.lightY = lightY
                state.lightZ = lightZ
                state.movingBlock.blockPos = lightPosition
                state.movingBlock.biome = level.getBiome(lightPosition)
            }
            val baseLighting = level.cardinalLighting()
            val rotatedLighting = if (state.lightingBase != baseLighting || state.lightingRotation != rotation) {
                state.lightingBase = baseLighting
                state.lightingRotation = rotation
                PhysicsPropLighting.rotate(baseLighting, rotation).also { state.rotatedLighting = it }
            } else state.rotatedLighting ?: PhysicsPropLighting.rotate(baseLighting, rotation)
            state.movingBlock.cardinalLighting = rotatedLighting
            state.movingBlock.lightEngine = level.lightEngine
        }
    }

    override fun submit(
        state: PhysicsPropRenderState,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        cameraState: CameraRenderState
    ) {
        if (state.movingBlock.blockState.renderShape == RenderShape.MODEL) {
            poseStack.pushPose()
            poseStack.rotate(state.rotation)
            poseStack.translate(-0.5, -0.5, -0.5)
            collector.submitMovingBlock(poseStack, state.movingBlock, state.outlineColor)
            poseStack.popPose()
        }
        state.highlightFace?.let { face ->
            poseStack.pushPose()
            poseStack.rotate(state.rotation)
            collector.submitShapeOutline(
                poseStack,
                ToolGunFaceHighlight.shape(face),
                RenderTypes.lines(),
                0xFF3CDCFF.toInt(),
                3f,
                true
            )
            poseStack.popPose()
        }
        state.beamStart?.let { start ->
            collector.submitCustomGeometry(poseStack, RenderTypes.lines()) { pose, vertices ->
                PhysicsGunBeam.render(pose, vertices, start)
            }
        }
        super.submit(state, poseStack, collector, cameraState)
    }
}

private object ToolGunFaceHighlight {
    fun shape(face: Direction) = when (face) {
        Direction.UP -> Shapes.box(-0.5, 0.503, -0.5, 0.5, 0.507, 0.5)
        Direction.DOWN -> Shapes.box(-0.5, -0.507, -0.5, 0.5, -0.503, 0.5)
        Direction.NORTH -> Shapes.box(-0.5, -0.5, -0.507, 0.5, 0.5, -0.503)
        Direction.SOUTH -> Shapes.box(-0.5, -0.5, 0.503, 0.5, 0.5, 0.507)
        Direction.WEST -> Shapes.box(-0.507, -0.5, -0.5, -0.503, 0.5, 0.5)
        Direction.EAST -> Shapes.box(0.503, -0.5, -0.5, 0.507, 0.5, 0.5)
    }
}

private object PhysicsGunBeam {
    fun render(
        pose: PoseStack.Pose,
        vertices: com.mojang.blaze3d.vertex.VertexConsumer,
        start: Vec3
    ) {
        val end = Vec3.ZERO
        val normal = end.subtract(start)
        if (normal.lengthSqr() < 1e-8) return
        val direction = normal.normalize()
        line(pose, vertices, start, end, direction, 30, 115, 170, 5f)
        line(pose, vertices, start, end, direction, 185, 245, 255, 2f)
    }

    private fun line(
        pose: PoseStack.Pose,
        vertices: com.mojang.blaze3d.vertex.VertexConsumer,
        start: Vec3,
        end: Vec3,
        direction: Vec3,
        red: Int,
        green: Int,
        blue: Int,
        width: Float
    ) {
        for (point in arrayOf(start, end)) {
            vertices.addVertex(pose, point.x.toFloat(), point.y.toFloat(), point.z.toFloat())
                .setColor(red, green, blue, 255)
                .setNormal(pose, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
                .setLineWidth(width)
        }
    }
}
