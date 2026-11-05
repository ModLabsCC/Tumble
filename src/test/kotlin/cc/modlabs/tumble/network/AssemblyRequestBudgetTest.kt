// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.network

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssemblyRequestBudgetTest {
    @Test
    fun `repeated requests for one assembly are limited to once per second`() {
        val budget = AssemblyRequestBudget()
        val id = UUID.randomUUID()
        assertTrue(budget.allow(id, 100, 200_000))
        assertFalse(budget.allow(id, 101, 200_000))
        assertFalse(budget.allow(id, 119, 200_000))
        assertTrue(budget.allow(id, 120, 200_000))
    }

    @Test
    fun `large assemblies share a per-player byte budget`() {
        val budget = AssemblyRequestBudget()
        assertTrue(budget.allow(UUID.randomUUID(), 100, 230_000))
        assertTrue(budget.allow(UUID.randomUUID(), 100, 230_000))
        assertFalse(budget.allow(UUID.randomUUID(), 100, 230_000))
        assertTrue(budget.allow(UUID.randomUUID(), 120, 230_000))
    }
}
