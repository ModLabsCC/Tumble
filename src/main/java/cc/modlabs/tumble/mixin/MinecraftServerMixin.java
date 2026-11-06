// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = {"reportChunkLoadFailure", "reportChunkSaveFailure"}, at = @At("HEAD"))
    private void tumble$recordChunkIoFailure(Throwable error, RegionStorageInfo storage, ChunkPos pos, CallbackInfo ci) {
        PhysicsServices.INSTANCE.chunkIoFailed((MinecraftServer) (Object) this);
    }
}
