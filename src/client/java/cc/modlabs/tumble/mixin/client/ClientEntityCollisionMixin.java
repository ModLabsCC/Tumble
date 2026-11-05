// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin.client;

import cc.modlabs.tumble.client.physics.ClientActorCollision;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class ClientEntityCollisionMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void tumble$clipLocalPlayerMovement(Vec3 movement, CallbackInfoReturnable<Vec3> callback) {
        if ((Object) this instanceof LocalPlayer player) {
            callback.setReturnValue(ClientActorCollision.INSTANCE.clipMovement(player, callback.getReturnValue()));
        }
    }
}
