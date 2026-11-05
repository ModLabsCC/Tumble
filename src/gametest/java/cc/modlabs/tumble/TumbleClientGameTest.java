// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble;

import cc.modlabs.tumble.entity.PhysicsPropEntity;
import cc.modlabs.tumble.item.TumbleItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class TumbleClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.CREATIVE);
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(TumbleItems.INSTANCE.getPHYSICS_GUN()));
                var pos = player.blockPosition().offset(1, 0, 0);
                player.level().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                var result = TumbleItems.INSTANCE.getPHYSICS_GUN().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
                if (result != InteractionResult.SUCCESS_SERVER) throw new AssertionError("Block conversion failed");
            });
            context.waitFor(client -> {
                if (client.level == null || client.player == null) return false;
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity instanceof PhysicsPropEntity prop && prop.getHeldByEntityId() == client.player.getId()) return true;
                }
                return false;
            });
            context.waitTicks(10);
        }
    }
}
