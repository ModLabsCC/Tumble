// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.mixin;

import cc.modlabs.tumble.physics.PhysicsServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PistonBaseBlock.class)
abstract class PistonBaseBlockMixin {
    @Shadow
    private boolean moveBlocks(Level level, BlockPos position, Direction direction, boolean extending) {
        throw new AssertionError();
    }

    @Redirect(
        method = "triggerEvent",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/piston/PistonBaseBlock;moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z"
        )
    )
    private boolean tumble$movePhysicsProps(
        PistonBaseBlock piston,
        Level level,
        BlockPos position,
        Direction direction,
        boolean extending
    ) {
        boolean capture = false;
        if (level instanceof ServerLevel serverLevel) {
            PistonStructureResolver resolver = new PistonStructureResolver(level, position, direction, extending);
            if (resolver.resolve()) {
                capture = PhysicsServices.INSTANCE.beginPistonMove(
                    serverLevel,
                    position,
                    direction,
                    extending,
                    resolver.getToPush()
                );
            }
        }

        boolean successful = false;
        try {
            successful = moveBlocks(level, position, direction, extending);
            return successful;
        } finally {
            if (capture) PhysicsServices.INSTANCE.endPistonMove(successful);
        }
    }
}
