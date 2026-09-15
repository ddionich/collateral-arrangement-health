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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EdgeCaseTest : FunSpec({

    val btc = Asset("BTC");
    val usdc = Asset("USDC")
    val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80))
    val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }

    test("zero requirement with zero collateral is Good Standing (assumption H9, not Liquidation)") {
        // With collateral = 0 every limit is 0; without the guard, 0 >= 0 would fall
        // through to Liquidation. Limits.locate special-cases requirement.isZero.
        val ca = CollateralArrangement(
            id = CaId("ca-zero"),
            collateral = Money("0".toBigDecimal(), btc),
            requirement = Money("0".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        CollateralHealth.assess(ca, Event.Recompute(), prices).status shouldBe Status.GOOD_STANDING
    }

    test("zero collateral with a positive requirement is Liquidation") {
        // Every limit is 0, and any positive requirement is at or above a 0 limit.
        val ca = CollateralArrangement(
            id = CaId("ca-zero-collateral"),
            collateral = Money("0".toBigDecimal(), btc),
            requirement = Money("1".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        CollateralHealth.assess(ca, Event.Recompute(), prices).status shouldBe Status.LIQUIDATION
    }

    test("a missing price is an explicit error, never a silent status") {
        val ca = CollateralArrangement(
            id = CaId("ca-no-price"),
            collateral = Money("2".toBigDecimal(), btc),
            requirement = Money("42000".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        val noPrice = PriceSource { base, quote -> error("no price for $base/$quote") }
        shouldThrow<IllegalStateException> {
            CollateralHealth.assess(ca, Event.Recompute(), noPrice)
        }
    }

    test("an incompatible price quote is rejected rather than silently misclassified") {
        val ca = CollateralArrangement(
            id = CaId("ca-bad-quote"),
            collateral = Money("2".toBigDecimal(), btc),
            requirement = Money("42000".toBigDecimal(), usdc),
            ltvs = ltvs,
        )
        // A price source that answers in the wrong asset - the collateral would be
        // valued in EUR while the requirement is in USDC, so they can never be compared.
        val wrongQuote = PriceSource { base, _ -> Rate("30000".toBigDecimal(), base, Asset("EUR")) }
        shouldThrow<IllegalArgumentException> {
            CollateralHealth.assess(ca, Event.Recompute(), wrongQuote)
        }
    }

    test("LTVs out of order are rejected at construction, not at assessment time") {
        shouldThrow<IllegalArgumentException> {
            LtvSet(Ltv.ofPercent(80), Ltv.ofPercent(65), Ltv.ofPercent(50))
        }
    }
})
