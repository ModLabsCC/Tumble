// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShulkerBoxBlockEntity.class)
abstract class ShulkerBoxBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private static void tumble$invalidateBeforeAnimation(Level level, BlockPos pos, BlockState state,
                                                         ShulkerBoxBlockEntity box, CallbackInfo callback) {
        var status = box.getAnimationStatus();
        if (level instanceof ServerLevel serverLevel &&
            (status == ShulkerBoxBlockEntity.AnimationStatus.OPENING || status == ShulkerBoxBlockEntity.AnimationStatus.CLOSING))
            PhysicsServices.INSTANCE.beforeTerrainChange(serverLevel, pos);
    }
}
