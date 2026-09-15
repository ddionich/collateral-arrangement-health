package com.dionich.collateral.health.model

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusTest {
    @Test
    fun `Status enum order remains in severity order`() {
        val expectedOrder = listOf(
            Status.GOOD_STANDING,
            Status.NEAR_MARGIN,
            Status.INITIAL_MARGIN_CALL,
            Status.MAINTENANCE_MARGIN_CALL,
            Status.LIQUIDATION,
        )
        assertEquals(expectedOrder, Status.entries)
    }

    @Test
    fun `Status ordinal respects severity hierarchy`() {
        val statuses = Status.entries
        for (i in 0 until statuses.size - 1) {
            assert(statuses[i] < statuses[i + 1]) {
                "Status ${statuses[i]} should be less severe than ${statuses[i + 1]}"
            }
        }
    }
}
