// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
abstract class ServerExplosionMixin {
    @Inject(method = "explode", at = @At("HEAD"))
    private void tumble$pushPhysicsProps(CallbackInfoReturnable<Integer> callback) {
        ServerExplosion explosion = (ServerExplosion) (Object) this;
        PhysicsServices.INSTANCE.explosion(explosion.level(), explosion.center());
    }

    @ModifyArg(
        method = "explode",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerExplosion;interactWithBlocks(Ljava/util/List;)V"
        )
    )
    private List<BlockPos> tumble$convertExplodedBlocks(List<BlockPos> positions) {
        ServerExplosion explosion = (ServerExplosion) (Object) this;
        return PhysicsServices.INSTANCE.convertExplodedBlocks(explosion.level(), explosion.center(), positions);
    }
}
