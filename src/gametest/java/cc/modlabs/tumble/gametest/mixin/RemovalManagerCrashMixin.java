// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest.mixin;
import cc.modlabs.tumble.gametest.RemovalCrashHook;
import cc.modlabs.tumble.entity.PhysicsPropEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "cc.modlabs.tumble.physics.PhysicsPropManager", remap = false)
abstract class RemovalManagerCrashMixin {
    @Inject(method = "beginPropRemoval", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;save(Lnet/minecraft/util/ProgressListener;ZZ)V"), remap = false)
    private void tumble$testRemovalTicket(PhysicsPropEntity prop, ServerLevel level, CallbackInfoReturnable<Boolean> ci) {
        if (!Boolean.getBoolean("tumble.removal.ticketProbe")) return;
        var chunk = net.minecraft.world.level.ChunkPos.containing(prop.blockPosition());
        if (!level.areEntitiesActuallyLoadedAndTicking(chunk)) throw new IllegalStateException("ticket did not activate entity ticking");
        level.getChunkSource().tick(() -> true, true);
        if (prop.isRemoved()) throw new IllegalStateException("ticket did not prevent entity unload");
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("removal-ticket-probe-reached.txt"), "ticking and retained"); }
        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    @Inject(method = "checkpointRemoval", at = @At("HEAD"), remap = false)
    private void tumble$beforeRemovalCheckpoint(UUID id, ServerLevel level, CallbackInfo ci) throws Exception {
        if ("verify".equals(System.getProperty("tumble.removal.phase")) && Boolean.getBoolean("tumble.removal.replayCrash")) {
            level.save(null, true, false);
            java.nio.file.Files.writeString(java.nio.file.Path.of("removal-replay-crash-reached.txt"), id.toString());
            Runtime.getRuntime().halt(87);
        }
        RemovalCrashHook.crash("removed", null);
    }
    @Inject(method = "checkpointRemoval", at = @At("RETURN"), remap = false)
    private void tumble$afterRemovalCheckpoint(UUID id, ServerLevel level, CallbackInfo ci) throws Exception {
        RemovalCrashHook.crash("checkpointed", null);
    }
}

