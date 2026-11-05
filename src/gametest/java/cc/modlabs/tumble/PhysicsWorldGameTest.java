// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class PhysicsWorldGameTest {
    @GameTest
    public void nativeWorldCanStepAndClose(GameTestHelper test) {
        PhysicsWorld world = new PhysicsWorld();
        world.step();
        world.close();
        world.close();
        try {
            world.step();
            throw new AssertionError("A closed world must not be stepped");
        } catch (IllegalStateException expected) {
            test.succeed();
        }
    }
}
