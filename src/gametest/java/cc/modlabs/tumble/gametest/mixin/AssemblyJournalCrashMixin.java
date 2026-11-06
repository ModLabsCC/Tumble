// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "cc.modlabs.tumble.physics.AssemblyConversionJournal", remap = false)
abstract class AssemblyJournalCrashMixin {
    @Inject(method = "prepare", at = @At("RETURN"), remap = false)
    private void tumble$crashAfterPrepare(UUID id, byte[] payload, CallbackInfo callback) throws Exception {
        if (!"prepare".equals(System.getProperty("tumble.restart.flush"))) return;
        Files.writeString(Path.of("assembly-crash-reached.txt"), "prepare: " + id);
        Runtime.getRuntime().halt(86);
    }
}
