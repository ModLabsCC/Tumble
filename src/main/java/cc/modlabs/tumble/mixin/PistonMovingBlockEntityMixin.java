// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private static void tumble$invalidateBeforeProgress(Level level, BlockPos pos, BlockState state,
                                                        PistonMovingBlockEntity piston, CallbackInfo callback) {
        if (level instanceof ServerLevel serverLevel) PhysicsServices.INSTANCE.beforeTerrainChange(serverLevel, pos);
    }

    @Inject(method = "finalTick", at = @At("HEAD"))
    private void tumble$invalidateBeforeFinalProgress(CallbackInfo callback) {
        var piston = (PistonMovingBlockEntity) (Object) this;
        if (piston.getLevel() instanceof ServerLevel serverLevel)
            PhysicsServices.INSTANCE.beforeTerrainChange(serverLevel, piston.getBlockPos());
    }
}
