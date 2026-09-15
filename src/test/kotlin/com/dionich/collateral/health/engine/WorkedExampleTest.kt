package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.*
import com.dionich.collateral.health.money.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkedExampleTest {

    private val btc = Asset("BTC")
    private val usdc = Asset("USDC")
    private val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }

    private val ca = CollateralArrangement(
        id = CaId("ca-1"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money("42000".toBigDecimal(), usdc),
        ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80)),
    )

    @Test
    fun `derives the limits from the PDF worked example`() {
        val a = CollateralHealth.assess(ca, Event.Recompute(), prices)
        assertEquals(Money("60000".toBigDecimal(), usdc), a.collateralValue)
        assertEquals(Money("30000".toBigDecimal(), usdc), a.limits.initial)
        assertEquals(Money("39000".toBigDecimal(), usdc), a.limits.maintenance)
        assertEquals(Money("48000".toBigDecimal(), usdc), a.limits.liquidation)
    }

    @Test
    fun `an ordinary recompute at 42,000 USDC is a Maintenance Margin Call`() {
        assertEquals(
            Status.MAINTENANCE_MARGIN_CALL,
            CollateralHealth.assess(ca, Event.Recompute(Reason.PRICE_MOVE), prices).status,
        )
    }

    @Test
    fun `the same CA, just linked, is an Initial Margin Call`() {
        assertEquals(Status.INITIAL_MARGIN_CALL, CollateralHealth.assess(ca, Event.Link, prices).status)
    }

    @Test
    fun `same limits from a different balance and price - guards against the 30,000 coincidence`() {
        val other = ca.copy(collateral = Money("1.5".toBigDecimal(), btc))
        val a = CollateralHealth.assess(
            other,
            Event.Recompute(),
            PriceSource { _, _ -> Rate("40000".toBigDecimal(), btc, usdc) },
        )
        assertEquals(Money("60000".toBigDecimal(), usdc), a.collateralValue)
        assertEquals(Money("30000".toBigDecimal(), usdc), a.limits.initial)
        assertEquals(Status.MAINTENANCE_MARGIN_CALL, a.status)
    }
}
