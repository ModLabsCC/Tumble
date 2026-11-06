// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.item

import cc.modlabs.tumble.TumbleMod
import cc.modlabs.tumble.physics.PhysicsServices
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level

object TumbleItems {
    private val gravityGunKey = ResourceKey.create(Registries.ITEM, TumbleMod.id("gravity_gun"))
    private val physicsGunKey = ResourceKey.create(Registries.ITEM, TumbleMod.id("physics_gun"))
    private val toolGunKey = ResourceKey.create(Registries.ITEM, TumbleMod.id("tool_gun"))
    private val creativeTabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, TumbleMod.id("main"))

    val GRAVITY_GUN: Item = Registry.register(
        BuiltInRegistries.ITEM,
        gravityGunKey,
        GravityGunItem(Item.Properties().setId(gravityGunKey).stacksTo(1))
    )

    val PHYSICS_GUN: Item = Registry.register(
        BuiltInRegistries.ITEM,
        physicsGunKey,
        PhysicsGunItem(Item.Properties().setId(physicsGunKey).stacksTo(1))
    )

    val TOOL_GUN: Item = Registry.register(
        BuiltInRegistries.ITEM,
        toolGunKey,
        ToolGunItem(Item.Properties().setId(toolGunKey).stacksTo(1))
    )

    fun isGun(item: Item) = item === GRAVITY_GUN || item === PHYSICS_GUN

    fun register() {
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            creativeTabKey,
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 7)
                .title(Component.translatable("itemGroup.tumble"))
                .icon { ItemStack(GRAVITY_GUN) }
                .displayItems { _, output ->
                    output.accept(GRAVITY_GUN)
                    output.accept(PHYSICS_GUN)
                    output.accept(TOOL_GUN)
                }
                .build()
        )
    }
}

private class ToolGunItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val undone = PhysicsServices.undoToolGunWeld(player)
        player.sendOverlayMessage(Component.translatable(
            if (undone) "message.tumble.tool_gun.undo" else "message.tumble.tool_gun.nothing_to_undo"
        ))
        return if (undone) InteractionResult.SUCCESS_SERVER else InteractionResult.FAIL
    }
}

private class GravityGunItem(properties: Properties) : Item(properties) {
    override fun interactLivingEntity(
        stack: ItemStack,
        player: Player,
        target: LivingEntity,
        hand: InteractionHand
    ) = use(target.level(), player, hand)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val manager = PhysicsServices.runtime(serverLevel.server)?.manager ?: return InteractionResult.FAIL
        if (!manager.toggleHeld(player)) return InteractionResult.FAIL

        serverLevel.playSound(
            null,
            player.blockPosition(),
            SoundEvents.ENDERMAN_TELEPORT,
            SoundSource.PLAYERS,
            0.6f,
            1.5f
        )
        return InteractionResult.SUCCESS_SERVER
    }
}

private class PhysicsGunItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val manager = PhysicsServices.runtime(serverLevel.server)?.manager ?: return InteractionResult.FAIL
        if (!manager.releaseHeld(player)) return InteractionResult.PASS
        player.sendOverlayMessage(Component.translatable("message.tumble.physics_gun.released"))
        return InteractionResult.SUCCESS_SERVER
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        if (context.level.isClientSide) return InteractionResult.SUCCESS
        val player = context.player ?: return InteractionResult.FAIL
        val level = context.level as? ServerLevel ?: return InteractionResult.FAIL
        val manager = PhysicsServices.runtime(level.server)?.manager ?: return InteractionResult.FAIL
        if (manager.releaseHeld(player)) {
            player.sendOverlayMessage(Component.translatable("message.tumble.physics_gun.released"))
            return InteractionResult.SUCCESS_SERVER
        }
        val prop = manager.convertBlock(level, context.clickedPos, player as? net.minecraft.server.level.ServerPlayer ?: return InteractionResult.FAIL) ?: return InteractionResult.FAIL
        if (!manager.togglePhysicsGunHeld(player, prop)) return InteractionResult.FAIL
        player.sendOverlayMessage(Component.translatable("message.tumble.physics_gun.converted", prop.mass))
        level.playSound(null, context.clickedPos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.PLAYERS, 0.6f, 1.4f)
        return InteractionResult.SUCCESS_SERVER
    }
}
