// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.client.network

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientEntityInstancesTest {
    @Test
    fun `late unload cannot untrack replacement with same uuid`() {
        val tracker = ClientEntityInstances<Any>()
        val id = UUID.randomUUID()
        val old = Any()
        val replacement = Any()
        assertFalse(tracker.track(id, old))
        assertTrue(tracker.track(id, replacement))
        assertFalse(tracker.untrack(id, old))
        assertTrue(tracker.untrack(id, replacement))
        assertFalse(tracker.untrack(id, replacement))
    }
}
