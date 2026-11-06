// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
abstract class PistonMovingDataLoadMixin {
    @Inject(method = "loadAdditional", at = @At("HEAD"))
    private void tumble$invalidateBeforeDataLoad(ValueInput input, CallbackInfo callback) {
        var piston = (PistonMovingBlockEntity) (Object) this;
        if (piston.getLevel() instanceof ServerLevel level)
            PhysicsServices.INSTANCE.beforeTerrainChange(level, piston.getBlockPos());
    }
}
