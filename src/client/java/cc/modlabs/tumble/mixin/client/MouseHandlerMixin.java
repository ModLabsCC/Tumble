// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin.client;

import cc.modlabs.tumble.client.ClientPhysicsGunControls;
import cc.modlabs.tumble.client.ClientToolGunControls;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void tumble$physicsGunScroll(long window, double horizontal, double vertical, CallbackInfo callback) {
        if (ClientToolGunControls.INSTANCE.onScroll(vertical) ||
            ClientPhysicsGunControls.INSTANCE.onScroll(vertical)) callback.cancel();
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void tumble$physicsGunRotate(double elapsedTime, CallbackInfo callback) {
        MouseHandlerAccess access = (MouseHandlerAccess) this;
        if (ClientPhysicsGunControls.INSTANCE.onMouseTurn(access.tumble$getAccumulatedDX(), access.tumble$getAccumulatedDY())) {
            callback.cancel();
        }
    }
}
