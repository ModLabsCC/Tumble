// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.entity

import cc.modlabs.tumble.physics.PhysicsRotation
import cc.modlabs.tumble.physics.PhysicsVector
import kotlin.test.Test
import kotlin.test.assertEquals
import net.minecraft.core.RegistryAccess
import net.minecraft.util.ProblemReporter
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput

class PhysicsPropPersistenceTest {
    @Test
    fun `prop persistence round trips through Minecraft value storage`() {
        val expected = PhysicsPropPersistence(
            "minecraft:raw_copper_block",
            PhysicsRotation(0.0, 0.6, 0.0, 0.8),
            PhysicsVector(1.0, -2.0, 3.0),
            PhysicsVector(0.5, 1.5, -0.5),
            true
        )
        val output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING)
        expected.write(output)
        val input = TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, output.buildResult())

        assertEquals(expected, PhysicsPropPersistence.read(input))
    }
}
