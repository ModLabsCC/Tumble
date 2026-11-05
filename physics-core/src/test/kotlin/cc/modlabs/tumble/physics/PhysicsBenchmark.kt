// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

/** A repeatable CPU baseline, not a substitute for profiling a running server. */
fun main() {
    for (count in listOf(100, 500, 1000, 5000)) {
        PhysicsWorld(PhysicsWorldConfig(gravity = PhysicsVector.ZERO)).use { world ->
            repeat(count) { index ->
                world.createBody(
                    PhysicsBodyDefinition(
                        transform = PhysicsTransform(
                            PhysicsVector((index % 20) * 2.0, (index / 400) * 2.0, ((index / 20) % 20) * 2.0)
                        ),
                        linearVelocity = PhysicsVector(0.1, 0.0, 0.0)
                    )
                )
            }
            repeat(120) { world.step(); world.snapshots() }
            val stepSamples = LongArray(180)
            val snapshotSamples = LongArray(180)
            repeat(180) { index ->
                val start = System.nanoTime()
                world.step()
                val stepped = System.nanoTime()
                check(world.snapshots().size == count)
                stepSamples[index] = stepped - start
                snapshotSamples[index] = System.nanoTime() - stepped
            }
            val combinedSamples = LongArray(180) {
                val start = System.nanoTime()
                check(world.stepAndSnapshots().size == count)
                System.nanoTime() - start
            }
            fun report(samples: LongArray): String {
                samples.sort()
                return "median %.3f ms, p95 %.3f ms".format(
                    samples[samples.size / 2] / 1_000_000.0,
                    samples[samples.size * 95 / 100] / 1_000_000.0
                )
            }
            println("$count moving bodies: step ${report(stepSamples)}; snapshots ${report(snapshotSamples)}; combined ${report(combinedSamples)}")
        }
    }
}
