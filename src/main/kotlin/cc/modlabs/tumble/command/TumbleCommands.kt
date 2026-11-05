// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.command

import cc.modlabs.tumble.physics.PhysicsServices
import cc.modlabs.tumble.physics.PhysicsVector
import cc.modlabs.tumble.physics.TumbleTuningKey
import cc.modlabs.tumble.item.TumbleItems
import cc.modlabs.tumble.api.TumbleApi
import cc.modlabs.tumble.assembly.AssemblyDefinition
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.coordinates.BlockPosArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

object TumbleCommands {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("tumble")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.literal("physics")
                        .then(
                            Commands.literal("spawn")
                                .executes { spawn(it.source, 1) }
                                .then(
                                    Commands.argument("count", IntegerArgumentType.integer(1, 8192))
                                        .executes { spawn(it.source, IntegerArgumentType.getInteger(it, "count")) }
                                )
                        )
                        .then(
                            Commands.literal("benchmark")
                                .then(
                                    Commands.argument("count", IntegerArgumentType.integer(1, 8192))
                                        .executes { benchmark(it.source, IntegerArgumentType.getInteger(it, "count")) }
                                )
                        )
                        .then(Commands.literal("hud").executes { toggleHud(it.source) })
                        .then(Commands.literal("convert").executes { convert(it.source) })
                        .then(Commands.literal("clear").executes { clear(it.source) })
                        .then(Commands.literal("debug").executes { debug(it.source) })
                )
                .then(assemblyNode())
                .then(Commands.literal("gravitygun").executes { gravityGun(it.source) })
                .then(Commands.literal("physicsgun").executes { physicsGun(it.source) })
                .then(tuningNode())
        )
    }

    private fun assemblyNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("assembly")
        .then(Commands.argument("from", BlockPosArgument.blockPos())
            .then(Commands.argument("to", BlockPosArgument.blockPos()).executes { context ->
                assemble(context.source, BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to"))
            }))

    private fun assemble(source: CommandSourceStack, first: BlockPos, second: BlockPos): Int {
        val player = source.player ?: run {
            source.sendFailure(Component.literal("A player must select the assembly."))
            return 0
        }
        val width = kotlin.math.abs(first.x.toLong() - second.x.toLong()) + 1
        val height = kotlin.math.abs(first.y.toLong() - second.y.toLong()) + 1
        val depth = kotlin.math.abs(first.z.toLong() - second.z.toLong()) + 1
        if (width > MAX_ASSEMBLY_SELECTION_VOLUME || height > MAX_ASSEMBLY_SELECTION_VOLUME ||
            depth > MAX_ASSEMBLY_SELECTION_VOLUME || width * height * depth > MAX_ASSEMBLY_SELECTION_VOLUME
        ) {
            source.sendFailure(Component.literal("The selection is too large."))
            return 0
        }
        val positions = ArrayList<BlockPos>()
        for (position in BlockPos.betweenClosed(first, second)) {
            if (!source.level.isInWorldBounds(position) || !source.level.chunkSource.hasChunk(position.x shr 4, position.z shr 4)) {
                source.sendFailure(Component.literal("The selection includes an unloaded or invalid position."))
                return 0
            }
            if (!source.level.getBlockState(position).isAir) {
                positions += position.immutable()
                if (positions.size > AssemblyDefinition.MAX_BLOCKS) {
                    source.sendFailure(Component.literal("The selection contains too many blocks."))
                    return 0
                }
            }
        }
        if (positions.isEmpty()) {
            source.sendFailure(Component.literal("The selection contains no blocks."))
            return 0
        }
        val closest = positions.minBy { player.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) }
        positions.remove(closest)
        positions.add(0, closest)
        val id = TumbleApi.convertAssemblyAsOperator(source.level, positions, player)
        if (id == null) {
            source.sendFailure(Component.literal("Could not convert that selection into an assembly."))
            return 0
        }
        source.sendSuccess({ Component.literal("Created an assembly of ${positions.size} blocks.") }, false)
        return 1
    }

    private const val MAX_ASSEMBLY_SELECTION_VOLUME = 32768L

    private fun tuningNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("tuning").also { root ->
        TumbleTuningKey.entries.forEach { key ->
            root.then(
                Commands.literal(key.id)
                    .executes { showTuning(it.source, key) }
                    .then(
                        Commands.argument("value", DoubleArgumentType.doubleArg(key.minimum, key.maximum))
                            .executes { setTuning(it.source, key, DoubleArgumentType.getDouble(it, "value")) }
                    )
            )
        }
    }

    private fun showTuning(source: CommandSourceStack, key: TumbleTuningKey): Int {
        val manager = PhysicsServices.runtime(source.server)?.manager ?: return unavailable(source)
        source.sendSuccess({ Component.literal("${key.id} = ${manager.tuningValue(key)}") }, false)
        return 1
    }

    private fun setTuning(source: CommandSourceStack, key: TumbleTuningKey, value: Double): Int {
        if (key.integer && value % 1.0 != 0.0) {
            source.sendFailure(Component.literal("${key.id} must be an integer."))
            return 0
        }
        val manager = PhysicsServices.runtime(source.server)?.manager ?: return unavailable(source)
        if (!manager.setTuning(key, value)) return unavailable(source)
        source.sendSuccess({ Component.literal("${key.id} set to $value") }, true)
        return 1
    }

    private fun unavailable(source: CommandSourceStack): Int {
        source.sendFailure(Component.literal("Physics service is unavailable."))
        return 0
    }

    private fun convert(source: CommandSourceStack): Int {
        val player = source.player ?: run {
            source.sendFailure(Component.literal("This command must be run by a player."))
            return 0
        }
        val hit = player.pick(8.0, 0f, false)
        if (hit.type != HitResult.Type.BLOCK || hit !is BlockHitResult) {
            source.sendFailure(Component.literal("Look at a solid block within eight blocks."))
            return 0
        }
        val prop = PhysicsServices.runtime(source.server)?.manager?.convertBlock(source.level, hit.blockPos, player)
        if (prop == null) {
            source.sendFailure(Component.literal("That block cannot safely become a physics prop."))
            return 0
        }
        source.sendSuccess({ Component.literal("Converted block to physics prop ${prop.uuid}.") }, true)
        return 1
    }

    private fun gravityGun(source: CommandSourceStack): Int {
        return giveGun(source, TumbleItems.GRAVITY_GUN, "item.tumble.gravity_gun")
    }

    private fun physicsGun(source: CommandSourceStack): Int {
        return giveGun(source, TumbleItems.PHYSICS_GUN, "item.tumble.physics_gun")
    }

    private fun giveGun(source: CommandSourceStack, item: net.minecraft.world.item.Item, translation: String): Int {
        val player = source.player ?: run {
            source.sendFailure(Component.literal("This command must be run by a player."))
            return 0
        }
        val stack = ItemStack(item)
        if (!player.inventory.add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.SERVER_ONLY)
        source.sendSuccess({ Component.translatable(translation) }, false)
        return 1
    }

    private fun spawn(source: CommandSourceStack, count: Int): Int {
        val player = source.player
        if (player == null) {
            source.sendFailure(Component.literal("This command must be run by a player."))
            return 0
        }
        val runtime = PhysicsServices.runtime(source.server)
        if (runtime == null || !runtime.manager.canSpawn()) {
            source.sendFailure(Component.literal("Physics is unavailable or the prop limit was reached."))
            return 0
        }
        val target = player.eyePosition.add(player.lookAngle.scale(2.0))
        val width = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        var spawned = 0
        repeat(count) { index ->
            val x = target.x + (index % width) * 1.1
            val z = target.z + (index / width) * 1.1
            if (runtime.manager.spawn(
                    source.level,
                    PhysicsVector(x, maxOf(target.y, player.y + 1.0), z)
                ) != null
            ) spawned++
        }
        if (spawned == 0) {
            source.sendFailure(Component.literal("Failed to add the physics prop to the world."))
            return 0
        }
        source.sendSuccess({ Component.literal("Spawned $spawned Tumble physics prop(s).") }, false)
        return spawned
    }

    private fun benchmark(source: CommandSourceStack, count: Int): Int {
        val spawned = spawn(source, count)
        if (spawned <= 0) return 0
        val player = source.player ?: return spawned
        PhysicsServices.enablePhysicsDebug(player)
        source.sendSuccess({ Component.literal("Benchmark HUD enabled. Use /tumble physics hud to hide it.") }, false)
        return spawned
    }

    private fun toggleHud(source: CommandSourceStack): Int {
        val player = source.player ?: run {
            source.sendFailure(Component.literal("This command must be run by a player."))
            return 0
        }
        val enabled = PhysicsServices.togglePhysicsDebug(player)
        source.sendSuccess({ Component.literal("Physics benchmark HUD ${if (enabled) "enabled" else "disabled"}.") }, false)
        return 1
    }

    private fun clear(source: CommandSourceStack): Int {
        val runtime = PhysicsServices.runtime(source.server)
        if (runtime == null) {
            source.sendFailure(Component.literal("Physics service is unavailable."))
            return 0
        }
        val count = runtime.manager.clear(source.level)
        source.sendSuccess({ Component.literal("Removed $count Tumble physics prop(s).") }, true)
        return count
    }

    private fun debug(source: CommandSourceStack): Int {
        val runtime = PhysicsServices.runtime(source.server)
        if (runtime == null) {
            source.sendFailure(Component.literal("Physics service is unavailable."))
            return 0
        }
        val state = runtime.service.debugState()
        val network = runtime.publisher.debugState()
        source.sendSuccess({
            Component.literal(
                "physics=${state.state} thread=${state.threadAlive} dimensions=${state.activeDimensions} " +
                    "props=${runtime.manager.registeredPropCount()} bodies=${state.runtimeBodies} " +
                    "terrain=${state.terrainSections}/${runtime.terrain.syncedSectionCount()} " +
                    "dirty=${runtime.terrain.dirtySectionCount()} queue=${state.queuedCommands} " +
                    "avg=${"%.3f".format(state.averageWorkMillis)}ms " +
                    "net=${network.publicationRateHz}Hz step=${network.latestPhysicsStep} seq=${network.publicationSequence} " +
                    "tracking=${network.trackingPlayers}/${network.trackingRelationships} " +
                    "sent=${network.statesSentLastTick}/${network.payloadsSentLastTick} " +
                    "sleepOmitted=${network.sleepingStatesOmittedLastTick} " +
                    "actorContacts=${runtime.manager.actorContactsLastTick} " +
                    "actorImpulses=${runtime.manager.actorImpulsesLastTick}" +
                    " held=${runtime.manager.heldPropCount()}" +
                    " launched=${runtime.manager.launchedPropCount()}" +
                    " explosionProps=${runtime.manager.explosionPropsLastTick}" +
                    (state.failure?.let { " failure=${it.lineSequence().first()}" } ?: "")
            )
        }, false)
        return 1
    }
}
