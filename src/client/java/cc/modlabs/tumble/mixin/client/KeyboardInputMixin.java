// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin.client;

import cc.modlabs.tumble.client.ClientPhysicsGunControls;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void tumble$freezeMovementWhileRotating(CallbackInfo callback) {
        if (ClientPhysicsGunControls.INSTANCE.isRotationMode()) {
            ClientInputAccess access = (ClientInputAccess) this;
            access.tumble$setKeyPresses(Input.EMPTY);
            access.tumble$setMoveVector(Vec2.ZERO);
        }
    }
}
