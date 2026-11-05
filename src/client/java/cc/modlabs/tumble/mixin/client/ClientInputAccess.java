// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientInput.class)
interface ClientInputAccess {
    @Accessor("keyPresses") void tumble$setKeyPresses(Input input);
    @Accessor("moveVector") void tumble$setMoveVector(Vec2 movement);
}
