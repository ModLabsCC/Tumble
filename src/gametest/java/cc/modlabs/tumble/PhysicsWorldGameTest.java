// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble;

import java.util.concurrent.atomic.AtomicReference;

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
    @GameTest
    public void nativeWorldRejectsAnotherThread(GameTestHelper test) throws InterruptedException {
        try (PhysicsWorld world = new PhysicsWorld()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    world.step();
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            thread.start();
            thread.join();
            if (!(failure.get() instanceof IllegalStateException)) {
                throw new AssertionError("A different thread must not step the world", failure.get());
            }
            world.step();
        }
        test.succeed();
    }
}
