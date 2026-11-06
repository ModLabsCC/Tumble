// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void tumble$protectConversionSources(
        BlockPos position,
        BlockState newState,
        int flags,
        CallbackInfoReturnable<BlockState> callback
    ) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level instanceof ServerLevel serverLevel && PhysicsServices.INSTANCE.conversionWriteBlocked(serverLevel, position, newState)) {
            callback.setReturnValue(null);
        } else if (level instanceof ServerLevel serverLevel && ((LevelChunk) (Object) this).getBlockState(position) != newState) {
            PhysicsServices.INSTANCE.beforeTerrainChange(serverLevel, position);
        }
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void tumble$markTerrainDirty(
        BlockPos position,
        BlockState newState,
        int flags,
        CallbackInfoReturnable<BlockState> callback
    ) {
        BlockState previous = callback.getReturnValue();
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (previous != null && previous != newState && level instanceof ServerLevel serverLevel) {
            PhysicsServices.INSTANCE.blockChanged(serverLevel, position, previous);
        }
    }
}
