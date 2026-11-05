// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import cc.modlabs.box3d.global.Box3D.b3CreateWorld
import cc.modlabs.box3d.global.Box3D.b3DefaultWorldDef
import cc.modlabs.box3d.global.Box3D.b3DestroyWorld
import cc.modlabs.box3d.global.Box3D.b3World_Step
import kotlin.test.Test

class Box3dNativeSmokeTest {
    @Test
    fun `native binding creates and steps a world`() {
        val world = b3CreateWorld(b3DefaultWorldDef())
        try {
            b3World_Step(world, 1f / 60f, 4)
        } finally {
            b3DestroyWorld(world)
        }
    }
}
