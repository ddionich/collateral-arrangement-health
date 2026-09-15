package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.CaId
import com.dionich.collateral.health.model.CollateralArrangement
import com.dionich.collateral.health.model.Event
import com.dionich.collateral.health.model.Status
import com.dionich.collateral.health.money.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The PDF's precedence rule ("when a requirement lands exactly on a shared threshold,
 * the more severe status wins") needs no special-case code: evaluating the ladder
 * top-down (Limits.locate) makes it structural. When LTVs are equal the less severe
 * bands are empty, so a requirement sitting exactly on the shared threshold falls
 * through to the most severe status automatically (see README, H5).
 */
class PrecedenceTest {

    private val btc = Asset("BTC")
    private val usdc = Asset("USDC")
    private val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }

    private fun ca(ltvs: LtvSet, requirement: String) = CollateralArrangement(
        id = CaId("ca-precedence"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money(requirement.toBigDecimal(), usdc),
        ltvs = ltvs,
    )

    @Test
    fun `all three LTVs equal, requirement exactly at the shared limit - Liquidation wins`() {
        val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(50), Ltv.ofPercent(50))
        // collateral value = 60,000; the single shared limit is 30,000
        assertEquals(Status.LIQUIDATION, CollateralHealth.assess(ca(ltvs, "30000"), Event.Recompute(), prices).status)
    }

    @Test
    fun `Initial equals Maintenance less than Liquidation, requirement at the shared threshold - Maintenance Margin Call wins`() {
        val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(50), Ltv.ofPercent(80))
        // Initial and Maintenance limits both 30,000; Liquidation limit is 48,000
        assertEquals(
            Status.MAINTENANCE_MARGIN_CALL,
            CollateralHealth.assess(ca(ltvs, "30000"), Event.Recompute(), prices).status,
        )
    }

    @Test
    fun `Initial less than Maintenance equals Liquidation, requirement at the shared threshold - Liquidation wins`() {
        val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(80), Ltv.ofPercent(80))
        // Maintenance and Liquidation limits both 48,000; Initial limit is 30,000
        assertEquals(Status.LIQUIDATION, CollateralHealth.assess(ca(ltvs, "48000"), Event.Recompute(), prices).status)
    }
}
