// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest.mixin;

import cc.modlabs.tumble.gametest.PlacementCrashHook;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "cc.modlabs.tumble.physics.PhysicsPropManager", remap = false)
abstract class PlacementManagerCrashMixin {
    @Inject(method = "checkpointPlacement", at = @At("HEAD"), remap = false)
    private void tumble$crashBeforeCheckpoint(UUID id, ServerLevel level, CallbackInfo callback) throws Exception {
        if ("verify".equals(System.getProperty("tumble.placement.phase")) && Boolean.getBoolean("tumble.placement.replayCrash")) {
            level.save(null, true, false);
            java.nio.file.Files.writeString(java.nio.file.Path.of("placement-replay-crash-reached.txt"), id.toString());
            Runtime.getRuntime().halt(87);
        }
        PlacementCrashHook.crash("removed", null);
    }
}
