// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysicsTrackingManagerTest {
    @Test
    fun `tracking start forces reset and stop removes delivery`() {
        val tracking = PhysicsTrackingManager()
        val prop = UUID.randomUUID()
        val player = UUID.randomUUID()

        tracking.startTracking(prop, player)
        assertTrue(tracking.publicationFlags(prop, player, 1, false)!! and PhysicsStateFlags.RESET != 0)
        tracking.stopTracking(prop, player)

        assertNull(tracking.publicationFlags(prop, player, 1, false))
        assertEquals(0, tracking.relationshipCount())
    }

    @Test
    fun `sleep sends once wake resumes and generation resets`() {
        val tracking = PhysicsTrackingManager()
        val prop = UUID.randomUUID()
        val player = UUID.randomUUID()
        tracking.startTracking(prop, player)
        tracking.publicationFlags(prop, player, 1, false)

        assertTrue(tracking.publicationFlags(prop, player, 1, true)!! and PhysicsStateFlags.SLEEP_TRANSITION != 0)
        assertNull(tracking.publicationFlags(prop, player, 1, true))
        assertTrue(tracking.publicationFlags(prop, player, 1, false)!! and PhysicsStateFlags.WAKE_TRANSITION != 0)
        assertTrue(tracking.publicationFlags(prop, player, 2, false)!! and PhysicsStateFlags.RESET != 0)
    }

    @Test
    fun `players receive only their tracked props and removal clears all relationships`() {
        val tracking = PhysicsTrackingManager()
        val firstProp = UUID.randomUUID()
        val secondProp = UUID.randomUUID()
        val firstPlayer = UUID.randomUUID()
        val secondPlayer = UUID.randomUUID()
        tracking.startTracking(firstProp, firstPlayer)
        tracking.startTracking(secondProp, secondPlayer)

        assertEquals(setOf(firstProp), tracking.propsTrackedBy(firstPlayer))
        assertEquals(setOf(secondPlayer), tracking.removeProp(secondProp))
        assertFalse(secondProp in tracking.propsTrackedBy(secondPlayer))
    }
}
