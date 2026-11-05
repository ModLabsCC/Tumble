// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin.client;

import cc.modlabs.tumble.client.ClientPhysicsGunControls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Shadow @Final public Options options;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void tumble$suppressInventoryWhileRotating(CallbackInfo callback) {
        if (ClientPhysicsGunControls.INSTANCE.isRotationMode()) {
            while (options.keyInventory.consumeClick()) { }
        }
    }
}
