package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.Band
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
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll

/**
 * Exactly the four properties from plan-kotlin.md §7 - no more. The transition
 * matrix already gives exhaustive coverage of the decision space; these are a
 * cross-cutting sanity net over the whole input space, not a second source of
 * documentation.
 */
class HealthPropertyTest : FunSpec({

    val btc = Asset("BTC"); val usdc = Asset("USDC")
    val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }
    val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80))
    val events = Arb.of(listOf<Event>(Event.Link, Event.Recompute(Reason.PRICE_MOVE)))

    fun ca(requirement: Long, prev: Status?) = CollateralArrangement(
        id = CaId("ca-property"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money(requirement.toBigDecimal(), usdc),
        ltvs = ltvs,
        currentStatus = prev,
    )

    test("P1: raising the requirement, everything else fixed, never yields a less severe status") {
        checkAll(
            Arb.enum<Status>().orNull(), events, Arb.long(0L..60_000L), Arb.long(0L..60_000L),
        ) { prev, event, base, delta ->
            val lower = CollateralHealth.assess(ca(base, prev), event, prices).status
            val higher = CollateralHealth.assess(ca(base + delta, prev), event, prices).status
            (higher >= lower) shouldBe true
        }
    }

    test("P2: the result is never less severe than the previous status, unless the requirement clears the Initial limit (a full cure)") {
        checkAll(Arb.enum<Status>(), events, Arb.long(0L..60_000L)) { prev, event, requirement ->
            val a = CollateralHealth.assess(ca(requirement, prev), event, prices)
            if (a.band != Band.BELOW_INITIAL) {
                (a.status >= prev) shouldBe true
            }
        }
    }

    test("P3: a link only produces Good Standing or Initial Margin Call, or leaves a preexisting Maintenance Margin Call / Liquidation untouched") {
        checkAll(Arb.enum<Status>().orNull(), Arb.long(0L..60_000L)) { prev, requirement ->
            val result = CollateralHealth.assess(ca(requirement, prev), Event.Link, prices).status
            val leftCalledStatusIntact = prev in Status.CALLED && result == prev
            (result == Status.GOOD_STANDING || result == Status.INITIAL_MARGIN_CALL || leftCalledStatusIntact) shouldBe true
        }
    }

    test("P4: with all three LTVs equal, the base classification for a recompute can only be Good Standing or Liquidation") {
        // Asserted against statusBeforeHistoryRules, not the final status: this property is
        // about the structural precedence rule (H5), which lives entirely in the base
        // classification step. The Initial Margin Call ceiling (rule 6) is a separate,
        // history-dependent concern, already covered exhaustively by TransitionMatrixTest -
        // it can hold the final status at Initial Margin Call even when the band collapses
        // to Good Standing/Liquidation only, so it must not be conflated with this property.
        checkAll(
            Arb.enum<Status>().orNull(), Arb.long(0L..60_000L), Arb.int(1..99),
        ) { prev, requirement, percent ->
            val equalLtv = Ltv.ofPercent(percent)
            val equalCa = CollateralArrangement(
                id = CaId("ca-equal-ltv"),
                collateral = Money("2".toBigDecimal(), btc),
                requirement = Money(requirement.toBigDecimal(), usdc),
                ltvs = LtvSet(equalLtv, equalLtv, equalLtv),
                currentStatus = prev,
            )
            val base = CollateralHealth.assess(equalCa, Event.Recompute(), prices).statusBeforeHistoryRules
            (base == Status.GOOD_STANDING || base == Status.LIQUIDATION) shouldBe true
        }
    }
})
