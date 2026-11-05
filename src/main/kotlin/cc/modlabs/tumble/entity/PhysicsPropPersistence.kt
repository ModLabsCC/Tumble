// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.entity

import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

data class PhysicsPropPersistence(
    val blockId: String,
    val rotation: PhysicsRotation,
    val linearVelocity: PhysicsVector,
    val angularVelocity: PhysicsVector,
    val frozen: Boolean = false
) {
    fun write(output: ValueOutput) {
        output.putString(BLOCK, blockId)
        output.putDouble(QX, rotation.x)
        output.putDouble(QY, rotation.y)
        output.putDouble(QZ, rotation.z)
        output.putDouble(QW, rotation.w)
        output.putDouble(LX, linearVelocity.x)
        output.putDouble(LY, linearVelocity.y)
        output.putDouble(LZ, linearVelocity.z)
        output.putDouble(AX, angularVelocity.x)
        output.putDouble(AY, angularVelocity.y)
        output.putDouble(AZ, angularVelocity.z)
        output.putBoolean(FROZEN, frozen)
    }

    companion object {
        fun read(input: ValueInput) = PhysicsPropPersistence(
            input.getStringOr(BLOCK, "minecraft:raw_copper_block"),
            PhysicsRotation(
                input.getDoubleOr(QX, 0.0),
                input.getDoubleOr(QY, 0.0),
                input.getDoubleOr(QZ, 0.0),
                input.getDoubleOr(QW, 1.0)
            ).normalized(),
            PhysicsVector(
                input.getDoubleOr(LX, 0.0),
                input.getDoubleOr(LY, 0.0),
                input.getDoubleOr(LZ, 0.0)
            ),
            PhysicsVector(
                input.getDoubleOr(AX, 0.0),
                input.getDoubleOr(AY, 0.0),
                input.getDoubleOr(AZ, 0.0)
            ),
            input.getBooleanOr(FROZEN, false)
        )

        private const val BLOCK = "TumbleBlock"
        private const val QX = "TumbleQX"
        private const val QY = "TumbleQY"
        private const val QZ = "TumbleQZ"
        private const val QW = "TumbleQW"
        private const val LX = "TumbleLinearX"
        private const val LY = "TumbleLinearY"
        private const val LZ = "TumbleLinearZ"
        private const val AX = "TumbleAngularX"
        private const val AY = "TumbleAngularY"
        private const val AZ = "TumbleAngularZ"
        private const val FROZEN = "TumbleFrozen"
    }
}
