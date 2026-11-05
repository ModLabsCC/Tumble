// SPDX-License-Identifier: MPL-2.0
package example.tumble;

import cc.modlabs.tumble.api.TumbleApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;

public final class SimpleAddon implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("tumble_example")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayer();
                    if (player == null) {
                        source.sendFailure(Component.literal("A player must run this command."));
                        return 0;
                    }
                    var position = player.getEyePosition().add(player.getLookAngle().scale(2));
                    var id = TumbleApi.spawn(source.getLevel(), position, Blocks.OAK_LOG.defaultBlockState());
                    if (id == null) {
                        source.sendFailure(Component.literal("Could not spawn the prop here."));
                        return 0;
                    }
                    source.sendSuccess(() -> Component.literal("Spawned prop " + id), false);
                    return 1;
                }))
        );
    }
}
