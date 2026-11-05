// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble;

import cc.modlabs.tumble.api.TumbleApi;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class TumbleAddonGameTest {
    @GameTest(maxTicks = 1600)
    public void addonCanSpawnAndMoveAServerProp(GameTestHelper test) throws InterruptedException {
        var level = test.getLevel();
        level.setChunkForced(30, 0, true);
        level.getChunkAt(new BlockPos(480, 0, 0));
        UUID prop = TumbleApi.spawn(level, new Vec3(481.5, 10, 1.5), Blocks.OAK_LOG.defaultBlockState());
        test.assertTrue(prop != null, "Addon could not spawn a prop");
        test.assertTrue(TumbleApi.status(level, prop) == TumbleApi.PropStatus.PENDING, "New prop was not pending");
        test.assertTrue(TumbleApi.spawn(level, new Vec3(481.5, 10, 1.5), Blocks.AIR.defaultBlockState()) == null,
            "Air was accepted as a prop");
        var wrongThread = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { TumbleApi.snapshot(level, prop); } catch (Throwable error) { wrongThread.set(error); }
        });
        thread.start();
        thread.join();
        test.assertTrue(wrongThread.get() instanceof IllegalStateException, "Off-thread API access was accepted");
        boolean rejected = false;
        try {
            TumbleApi.applyImpulse(level, prop, new Vec3(Double.MAX_VALUE, 0, 0));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        test.assertTrue(rejected, "Oversized impulse was accepted");
        final double[] startY = {Double.NaN};
        test.succeedWhen(() -> {
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new RuntimeException(e); }
            var snapshot = TumbleApi.snapshot(level, prop);
            test.assertTrue(snapshot != null, "Prop is not ready");
            if (Double.isNaN(startY[0])) {
                test.assertTrue(TumbleApi.status(level, prop) == TumbleApi.PropStatus.READY, "Ready prop had the wrong status");
                boolean tooLarge = false;
                try { TumbleApi.applyImpulse(level, prop, new Vec3(Float.MAX_VALUE, 0, 0)); }
                catch (IllegalArgumentException expected) { tooLarge = true; }
                test.assertTrue(tooLarge, "Large finite impulse was accepted");
                startY[0] = snapshot.getTransform().getPosition().getY();
                test.assertTrue(TumbleApi.applyImpulse(level, prop, new Vec3(0, 30, 0)), "Impulse rejected");
                test.assertTrue(false, "Waiting for impulse");
                return;
            }
            test.assertTrue(snapshot.getTransform().getPosition().getY() >= startY[0] + 0.5, "Impulse did not move prop");
            test.assertTrue(TumbleApi.remove(level, prop) && !TumbleApi.remove(level, prop), "Prop removal failed");
            test.assertTrue(TumbleApi.status(level, prop) == TumbleApi.PropStatus.UNAVAILABLE, "Removed prop remained available");
        });
    }
}
