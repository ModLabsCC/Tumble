// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @Redirect(
        method = "useOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;"
        )
    )
    private InteractionResult tumble$capturePlacedBlocks(
        BlockItem item,
        BlockPlaceContext context,
        UseOnContext ignored
    ) {
        if (!(context.getLevel() instanceof ServerLevel level)) return item.place(context);
        boolean capture = PhysicsServices.INSTANCE.beginBlockPlacement(level, context);
        InteractionResult result = null;
        try {
            result = item.place(context);
            return result;
        } finally {
            if (capture) {
                PhysicsServices.INSTANCE.endBlockPlacement(level, result != null && result.consumesAction());
            }
        }
    }
}
