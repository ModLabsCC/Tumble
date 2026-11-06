// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest.mixin;

import cc.modlabs.tumble.gametest.PlacementCrashHook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "cc.modlabs.tumble.physics.AssemblyConversionJournal", remap = false)
abstract class AssemblyJournalCrashMixin {
    @Shadow private Path directory;
    @Inject(method = "prepare", at = @At("RETURN"), remap = false)
    private void tumble$crashAfterPrepare(UUID id, byte[] payload, CallbackInfo callback) throws Exception {
        PlacementCrashHook.crash("prepared", directory);
        if (!"prepare".equals(System.getProperty("tumble.restart.flush"))) return;
        Files.writeString(Path.of("assembly-crash-reached.txt"), "prepare: " + id);
        Runtime.getRuntime().halt(86);
    }
    @Inject(method = "markStarted", at = @At("HEAD"), remap = false)
    private void tumble$crashBeforeStarted(UUID id, CallbackInfo callback) throws Exception {
        PlacementCrashHook.crash("beforeStarted", directory);
    }
    @Inject(method = "markStarted", at = @At("RETURN"), remap = false)
    private void tumble$crashAfterStarted(UUID id, CallbackInfo callback) throws Exception {
        PlacementCrashHook.crash("started", directory);
    }
}
