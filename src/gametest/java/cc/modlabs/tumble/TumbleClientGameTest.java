// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble;

import cc.modlabs.tumble.entity.PhysicsPropEntity;
import cc.modlabs.tumble.api.TumbleApi;
import cc.modlabs.tumble.client.PhysicsPropRenderer;
import cc.modlabs.tumble.client.physics.ClientAssemblyState;
import cc.modlabs.tumble.item.TumbleItems;
import cc.modlabs.tumble.physics.PhysicsServices;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
            var pigId = new AtomicReference<UUID>();
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var manager = PhysicsServices.INSTANCE.runtime(server).getManager();
                manager.releasePlayer(player.getUUID());
                var level = server.overworld();
                level.getChunkAt(new BlockPos(0, 100, 0));
                player.setPos(0.5, 100.0, 0.5);
                player.setYRot(0);
                player.setXRot(0);
                player.setNoGravity(true);
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(TumbleItems.INSTANCE.getGRAVITY_GUN()));
                level.setBlock(new BlockPos(0, 100, 3), Blocks.STONE.defaultBlockState(), 3);
                var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
                if (pig == null) throw new AssertionError("Could not create pig");
                pig.setPos(0.5, 101.0, 3.5);
                pig.setNoAi(true);
                if (!level.addFreshEntity(pig)) throw new AssertionError("Could not spawn pig");
                pigId.set(pig.getUUID());
            });
            context.waitTicks(3);
            world.getServer().runOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                var level = server.overworld();
                var pig = (LivingEntity) level.getEntity(pigId.get());
                if (pig == null || !PhysicsServices.INSTANCE.runtime(server).getManager().toggleHeld(player)) {
                    throw new AssertionError("Could not hold pig");
                }
                if (pig.isNoGravity()) throw new AssertionError("Holding changed the pig's saved gravity");
                level.setBlock(new BlockPos(0, 100, 3), Blocks.AIR.defaultBlockState(), 3);
            });
            context.waitTicks(40);
            world.getServer().runOnServer(server -> {
                var pig = (LivingEntity) server.overworld().getEntity(pigId.get());
                if (pig == null || !pig.isAlive() || pig.getY() < 100.3) {
                    throw new AssertionError("Held pig fell after its support was removed");
                }
            });
            var assemblyId = new AtomicReference<UUID>();
            world.getServer().runOnServer(server -> {
                var level = server.overworld();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                player.setPos(0.5, 100, 0.5);
                var first = new BlockPos(1, 100, 1);
                var second = new BlockPos(2, 100, 1);
                level.setBlock(first, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(second, Blocks.OAK_STAIRS.defaultBlockState(), 3);
                var id = TumbleApi.convertAssembly(level, java.util.List.of(first, second), player);
                if (id == null) throw new AssertionError("Could not convert the test assembly");
                assemblyId.set(id);
            });
            context.waitFor(client -> {
                if (client.level == null) return false;
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity instanceof PhysicsPropEntity prop && prop.getUUID().equals(assemblyId.get()) && prop.getAssembly() != null) {
                        var renderer = (PhysicsPropRenderer) client.getEntityRenderDispatcher().getRenderer(prop);
                        var renderState = renderer.createRenderState();
                        renderer.extractRenderState(prop, renderState, 0);
                        return prop.getAssembly().getDefinition().getBlocks().size() == 2 &&
                            prop.getBoundingBox().getXsize() > 1.9 && renderState.getAssemblyBlocks().size() == 2;
                    }
                }
                return false;
            });
            var droppedAssembly = new AtomicBoolean();
            context.waitFor(client -> {
                if (client.level == null) return false;
                for (var entity : client.level.entitiesForRendering()) {
                    if (entity instanceof PhysicsPropEntity prop && prop.getUUID().equals(assemblyId.get())) {
                        if (!droppedAssembly.get()) {
                            try {
                                var field = PhysicsPropEntity.class.getDeclaredField("assembly");
                                field.setAccessible(true);
                                field.set(prop, null);
                            } catch (ReflectiveOperationException error) {
                                throw new RuntimeException(error);
                            }
                            ClientAssemblyState.INSTANCE.untrack(prop.getUUID());
                            ClientAssemblyState.INSTANCE.track(prop);
                            droppedAssembly.set(true);
                            return false;
                        }
                        return prop.getAssembly() != null && prop.getAssembly().getDefinition().getBlocks().size() == 2;
                    }
                }
                return false;
            });
        }
    }
}
