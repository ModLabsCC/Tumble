// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

data class PhysicsBlockPosition(val x: Int, val y: Int, val z: Int) {
    fun section() = PhysicsSectionPosition(
        Math.floorDiv(x, SECTION_SIZE),
        Math.floorDiv(y, SECTION_SIZE),
        Math.floorDiv(z, SECTION_SIZE)
    )

    private companion object {
        const val SECTION_SIZE = 16
    }
}

data class PhysicsSectionPosition(val x: Int, val y: Int, val z: Int)

data class TerrainBox(val bounds: PhysicsBox, val material: PhysicsMaterial = PhysicsMaterial.DEFAULT)

data class TerrainBlockGeometry(
    val position: PhysicsBlockPosition,
    val boxes: List<TerrainBox>
)
