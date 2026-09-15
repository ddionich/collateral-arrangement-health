package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.CaId
import com.dionich.collateral.health.model.CollateralArrangement
import com.dionich.collateral.health.model.Event
import com.dionich.collateral.health.model.Status
import com.dionich.collateral.health.money.Asset
import com.dionich.collateral.health.money.Ltv
import com.dionich.collateral.health.money.LtvSet
import com.dionich.collateral.health.money.Money
import com.dionich.collateral.health.money.Rate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EdgeCaseTest {

    private val btc = Asset("BTC")
    private val usdc = Asset("USDC")
    private val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80))
    private val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }

    @Test
    fun `zero requirement with zero collateral is Good Standing (assumption H9, not Liquidation)`() {
        // With collateral = 0 every limit is 0; without the guard, 0 >= 0 would fall
        // through to Liquidation. Limits.locate special-cases requirement.isZero.
        val ca = CollateralArrangement(
            id = CaId("ca-zero"),
            collateral = Money("0".toBigDecimal(), btc),
            requirement = Money("0".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        assertEquals(Status.GOOD_STANDING, CollateralHealth.assess(ca, Event.Recompute(), prices).status)
    }

    @Test
    fun `zero collateral with a positive requirement is Liquidation`() {
        // Every limit is 0, and any positive requirement is at or above a 0 limit.
        val ca = CollateralArrangement(
            id = CaId("ca-zero-collateral"),
            collateral = Money("0".toBigDecimal(), btc),
            requirement = Money("1".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        assertEquals(Status.LIQUIDATION, CollateralHealth.assess(ca, Event.Recompute(), prices).status)
    }

    @Test
    fun `a missing price is an explicit error, never a silent status`() {
        val ca = CollateralArrangement(
            id = CaId("ca-no-price"),
            collateral = Money("2".toBigDecimal(), btc),
            requirement = Money("42000".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        val noPrice = PriceSource { base, quote -> error("no price for $base/$quote") }
        assertFailsWith<IllegalStateException> {
            CollateralHealth.assess(ca, Event.Recompute(), noPrice)
        }
    }

    @Test
    fun `an incompatible price quote is rejected rather than silently misclassified`() {
        val ca = CollateralArrangement(
            id = CaId("ca-bad-quote"),
            collateral = Money("2".toBigDecimal(), btc),
            requirement = Money("42000".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        // A price source that answers in the wrong asset - the collateral would be
        // valued in EUR while the requirement is in USDC, so they can never be compared.
        val wrongQuote = PriceSource { base, _ -> Rate("30000".toBigDecimal(), base, Asset("EUR")) }
        assertFailsWith<IllegalArgumentException> {
            CollateralHealth.assess(ca, Event.Recompute(), wrongQuote)
        }
    }

    @Test
    fun `LTVs out of order are rejected at construction, not at assessment time`() {
        assertFailsWith<IllegalArgumentException> {
            LtvSet(Ltv.ofPercent(80), Ltv.ofPercent(65), Ltv.ofPercent(50))
        }
    }
}
