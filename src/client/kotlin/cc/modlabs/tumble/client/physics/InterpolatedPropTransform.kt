// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector

data class InterpolatedPropTransform(
    val position: PhysicsVector,
    val rotation: PhysicsRotation
)

data class PropSample(
    val sequence: Long,
    val physicsStep: Long,
    val arrivalNanos: Long,
    val generation: Long,
    val position: PhysicsVector,
    val rotation: PhysicsRotation,
    val linearVelocity: PhysicsVector,
    val angularVelocity: PhysicsVector,
    val sleeping: Boolean,
    val flags: Int
) {
    val transform = InterpolatedPropTransform(position, rotation)
}
