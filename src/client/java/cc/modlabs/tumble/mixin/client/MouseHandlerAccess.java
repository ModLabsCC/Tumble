// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MouseHandler.class)
interface MouseHandlerAccess {
    @Accessor("accumulatedDX") double tumble$getAccumulatedDX();
    @Accessor("accumulatedDY") double tumble$getAccumulatedDY();
}
