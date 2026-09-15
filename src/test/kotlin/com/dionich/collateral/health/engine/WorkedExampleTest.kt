package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.CaId
import com.dionich.collateral.health.model.CollateralArrangement
import com.dionich.collateral.health.model.Event
import com.dionich.collateral.health.model.Reason
import com.dionich.collateral.health.model.Status
import com.dionich.collateral.health.money.Asset
import com.dionich.collateral.health.money.Ltv
import com.dionich.collateral.health.money.LtvSet
import com.dionich.collateral.health.money.Money
import com.dionich.collateral.health.money.Rate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WorkedExampleTest : FunSpec({

    val btc = Asset("BTC"); val usdc = Asset("USDC")
    val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }

    val ca = CollateralArrangement(
        id = CaId("ca-1"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money("42000".toBigDecimal(), usdc),
        ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80)),
    )

    test("derives the limits from the PDF worked example") {
        val a = CollateralHealth.assess(ca, Event.Recompute(), prices)
        a.collateralValue shouldBe Money("60000".toBigDecimal(), usdc)
        a.limits.initial     shouldBe Money("30000".toBigDecimal(), usdc)
        a.limits.maintenance shouldBe Money("39000".toBigDecimal(), usdc)
        a.limits.liquidation shouldBe Money("48000".toBigDecimal(), usdc)
    }

    test("an ordinary recompute at 42,000 USDC is a Maintenance Margin Call") {
        CollateralHealth.assess(ca, Event.Recompute(Reason.PRICE_MOVE), prices)
            .status shouldBe Status.MAINTENANCE_MARGIN_CALL
    }

    test("the same CA, just linked, is an Initial Margin Call") {
        CollateralHealth.assess(ca, Event.Link, prices).status shouldBe Status.INITIAL_MARGIN_CALL
    }

    test("same limits from a different balance and price - guards against the 30,000 coincidence") {
        val other = ca.copy(collateral = Money("1.5".toBigDecimal(), btc))
        val a = CollateralHealth.assess(other, Event.Recompute(), PriceSource { _, _ ->
            Rate("40000".toBigDecimal(), btc, usdc)
        })
        a.collateralValue shouldBe Money("60000".toBigDecimal(), usdc)
        a.limits.initial shouldBe Money("30000".toBigDecimal(), usdc)
        a.status shouldBe Status.MAINTENANCE_MARGIN_CALL
    }
})
