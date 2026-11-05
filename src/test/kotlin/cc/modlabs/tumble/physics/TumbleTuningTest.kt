// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TumbleTuningTest {
    @Test
    fun `values update inside declared safety range`() {
        val tuning = TumbleTuning()

        tuning.set(TumbleTuningKey.LAUNCH_SPEED, 40.0)

        assertEquals(40.0, tuning.get(TumbleTuningKey.LAUNCH_SPEED))
        assertEquals(4096.0, tuning.get(TumbleTuningKey.MAX_PHYSICS_OBJECTS))
        tuning.set(TumbleTuningKey.MAX_PHYSICS_OBJECTS, 8192.0)
    }

    @Test
    fun `invalid and fractional integer values are rejected`() {
        val tuning = TumbleTuning()

        assertFailsWith<IllegalArgumentException> { tuning.set(TumbleTuningKey.GRAB_DISTANCE, 100.0) }
        assertFailsWith<IllegalArgumentException> { tuning.set(TumbleTuningKey.MAX_PHYSICS_OBJECTS, 4.5) }
    }

    @Test
    fun `validated tuning persists across reload`() {
        val directory = Files.createTempDirectory("tumble-tuning-test")
        val file = directory.resolve("tumble.properties")
        try {
            TumbleTuning(file).set(TumbleTuningKey.GRAVITY, -12.0)

            assertEquals(-12.0, TumbleTuning(file).get(TumbleTuningKey.GRAVITY))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
