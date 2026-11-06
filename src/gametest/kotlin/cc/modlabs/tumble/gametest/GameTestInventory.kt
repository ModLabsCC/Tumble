// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest

import java.nio.file.Files
import java.nio.file.Path
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper

/** The unfiltered runner's registry selection, without running the full suite. */
class GameTestInventory {
    @GameTest
    fun dump(helper: GameTestHelper) {
        System.getProperty("tumble.test.inventoryFile")?.let { output ->
            val tests = helper.level.server.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE)
                .listElements().filter { !it.value().manualOnly() }
                .map { it.key().identifier().toString() }.sorted().toList()
            Files.write(Path.of(output), tests)
        }
        helper.succeed()
    }
}
