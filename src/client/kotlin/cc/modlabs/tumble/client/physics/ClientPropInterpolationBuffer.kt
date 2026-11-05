// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.physics

import cc.modlabs.tumble.network.PhysicsStateFlags
import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import kotlin.math.sqrt

class ClientPropInterpolationBuffer {
    private val samples = ArrayDeque<PropSample>(MAX_SAMPLES)

    fun add(sample: PropSample): AddResult {
        val latest = samples.lastOrNull()
        if (latest != null && sample.generation == latest.generation && sample.sequence <= latest.sequence) {
            return AddResult.STALE
        }
        val reset = latest == null ||
            sample.generation != latest.generation ||
            sample.flags and PhysicsStateFlags.RESET != 0 ||
            sample.arrivalNanos - latest.arrivalNanos > MAX_SAMPLE_GAP_NANOS ||
            (sample.position - latest.position).squaredLength() > HARD_CORRECTION_DISTANCE_SQUARED
        if (reset) samples.clear()
        samples += sample
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        return if (reset) AddResult.RESET else AddResult.ACCEPTED
    }

    fun sample(targetNanos: Long): InterpolatedPropTransform? {
        val first = samples.firstOrNull() ?: return null
        if (targetNanos <= first.arrivalNanos) return first.transform

        var previous = first
        var index = 1
        while (index < samples.size) {
            val next = samples[index++]
            if (targetNanos <= next.arrivalNanos) {
                val span = next.arrivalNanos - previous.arrivalNanos
                val alpha = if (span <= 0L) 1.0 else (targetNanos - previous.arrivalNanos).toDouble() / span
                return InterpolatedPropTransform(
                    previous.position + (next.position - previous.position) * alpha.coerceIn(0.0, 1.0),
                    nlerp(previous.rotation, next.rotation, alpha)
                )
            }
            previous = next
        }

        val lateNanos = targetNanos - previous.arrivalNanos
        if (previous.sleeping || lateNanos <= 0L || lateNanos > MAX_EXTRAPOLATION_NANOS) return previous.transform
        return InterpolatedPropTransform(
            previous.position + previous.linearVelocity * (lateNanos / 1_000_000_000.0),
            previous.rotation
        )
    }

    fun size() = samples.size
    fun generation() = samples.lastOrNull()?.generation

    enum class AddResult { ACCEPTED, RESET, STALE }

    companion object {
        const val MAX_SAMPLES = 6
        const val MAX_EXTRAPOLATION_NANOS = 100_000_000L
        const val MAX_SAMPLE_GAP_NANOS = 500_000_000L
        const val HARD_CORRECTION_DISTANCE_SQUARED = 64.0

        fun nlerp(first: PhysicsRotation, second: PhysicsRotation, alpha: Double): PhysicsRotation {
            val a = first.normalized()
            val b = second.normalized()
            val sign = if (a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w < 0.0) -1.0 else 1.0
            val t = alpha.coerceIn(0.0, 1.0)
            val x = a.x + (b.x * sign - a.x) * t
            val y = a.y + (b.y * sign - a.y) * t
            val z = a.z + (b.z * sign - a.z) * t
            val w = a.w + (b.w * sign - a.w) * t
            val inverseLength = 1.0 / sqrt(x * x + y * y + z * z + w * w)
            return PhysicsRotation(x * inverseLength, y * inverseLength, z * inverseLength, w * inverseLength)
        }
    }
}
