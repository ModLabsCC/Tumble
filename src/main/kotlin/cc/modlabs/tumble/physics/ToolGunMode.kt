// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

enum class ToolGunMode(val key: String, val needsTwoProps: Boolean) {
    WELD("weld", true),
    ROPE("rope", true),
    AXIS("axis", true),
    NO_COLLIDE("no_collide", true),
    WEIGHT_BUOYANCY("weight_buoyancy", false),
    REMOVER("remover", false);

    fun cycle(delta: Int): ToolGunMode {
        val modes = entries
        return modes[Math.floorMod(ordinal + delta, modes.size)]
    }
}

enum class ToolGunResult { SELECTED, APPLIED, CANCELLED, FAILED }
