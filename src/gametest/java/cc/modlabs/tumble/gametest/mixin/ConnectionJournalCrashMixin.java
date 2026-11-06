// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest.mixin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "cc.modlabs.tumble.physics.DurableConnectionJournal", remap = false)
abstract class ConnectionJournalCrashMixin {
    @Inject(method = "putAll", at = @At("RETURN"), remap = false)
    private void tumble$crashAfterConnectionIntent(Collection<?> records, CallbackInfo callback) throws Exception {
        if (!Boolean.getBoolean("tumble.connection.crash")) return;
        Files.writeString(Path.of("connection-crash-reached.txt"), "intent");
        Runtime.getRuntime().halt(87);
    }
}
