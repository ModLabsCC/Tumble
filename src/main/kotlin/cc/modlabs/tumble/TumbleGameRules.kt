// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder
import net.minecraft.world.level.gamerules.GameRule
import net.minecraft.world.level.gamerules.GameRuleCategory

object TumbleGameRules {
    val CONVERT_PLACED_BLOCKS: GameRule<Boolean> = booleanRule("convert_placed_blocks")
    val CONVERT_DROPPED_BLOCKS: GameRule<Boolean> = booleanRule("convert_dropped_blocks")
    val CONVERT_EXPLODED_BLOCKS: GameRule<Boolean> = booleanRule("convert_exploded_blocks")
    val EXPLODE_TNT_ON_IMPACT: GameRule<Boolean> = booleanRule("explode_tnt_on_impact")
    val REACT_TO_EXPLOSIONS: GameRule<Boolean> = booleanRule("react_to_explosions", true)

    fun register() = Unit

    private fun booleanRule(path: String, default: Boolean = false) = GameRuleBuilder.forBoolean(default)
        .category(GameRuleCategory.UPDATES)
        .buildAndRegister(TumbleMod.id(path))
}
