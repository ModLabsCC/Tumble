// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityMovementMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void tumble$clipActorMovement(Vec3 movement, CallbackInfoReturnable<Vec3> callback) {
        if ((Object) this instanceof LivingEntity actor && actor.level() instanceof ServerLevel) {
            callback.setReturnValue(PhysicsServices.INSTANCE.clipActorMovement(actor, callback.getReturnValue()));
        }
    }
}
