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

/**
 * The full 48-cell transition matrix from result.md §4: 6 previous statuses
 * (none + the 5 named statuses) x 4 bands x 2 events. Every cell is a decision;
 * the counter-intuitive ones (Initial Margin Call as a ceiling/floor, hysteresis on
 * Maintenance/Liquidation, a link leaving a called CA untouched) are documented in
 * README.md's assumptions table. Values are copied verbatim from result.md, not
 * re-derived here.
 *
 * Each band is represented by its exact lower boundary, so this table also covers
 * every ">=" threshold in the spec without a separate boundary test:
 *   B0 = 29,999.99 (below Initial)   B1 = 30,000 (at Initial)
 *   B2 = 39,000 (at Maintenance)     B3 = 48,000 (at Liquidation)
 */
class TransitionMatrixTest : FunSpec({

    val btc = Asset("BTC"); val usdc = Asset("USDC")
    val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }

    fun ca(requirement: String, prev: Status?) = CollateralArrangement(
        id = CaId("ca-matrix"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money(requirement.toBigDecimal(), usdc),
        ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80)),
        currentStatus = prev,
    )

    data class Band(val label: String, val requirement: String)
    val b0 = Band("B0 req<Initial", "29999.99")
    val b1 = Band("B1 req>=Initial", "30000")
    val b2 = Band("B2 req>=Maintenance", "39000")
    val b3 = Band("B3 req>=Liquidation", "48000")
    val bands = listOf(b0, b1, b2, b3)

    data class Prev(val label: String, val status: Status?)
    val none = Prev("none", null)
    val gs = Prev("Good Standing", Status.GOOD_STANDING)
    val nm = Prev("Near Margin", Status.NEAR_MARGIN)
    val imc = Prev("Initial MC", Status.INITIAL_MARGIN_CALL)
    val mmc = Prev("Maintenance MC", Status.MAINTENANCE_MARGIN_CALL)
    val liq = Prev("Liquidation", Status.LIQUIDATION)

    data class Case(val event: Event, val prev: Prev, val band: Band, val expected: Status)

    val recompute = Event.Recompute(Reason.PRICE_MOVE)
    val link = Event.Link

    // --- Ordinary recompute (rules 1-4 base classification, rule 6/7/8 history) ---
    val recomputeCases = listOf(
        // prev = none / Good Standing / Near Margin: pure base classification (rules 1-4)
        Case(recompute, none, b0, Status.GOOD_STANDING),
        Case(recompute, none, b1, Status.NEAR_MARGIN),
        Case(recompute, none, b2, Status.MAINTENANCE_MARGIN_CALL),
        Case(recompute, none, b3, Status.LIQUIDATION),

        Case(recompute, gs, b0, Status.GOOD_STANDING),
        Case(recompute, gs, b1, Status.NEAR_MARGIN),
        Case(recompute, gs, b2, Status.MAINTENANCE_MARGIN_CALL),
        Case(recompute, gs, b3, Status.LIQUIDATION),

        Case(recompute, nm, b0, Status.GOOD_STANDING),
        Case(recompute, nm, b1, Status.NEAR_MARGIN),
        Case(recompute, nm, b2, Status.MAINTENANCE_MARGIN_CALL),
        Case(recompute, nm, b3, Status.LIQUIDATION),

        // prev = Initial MC: rule 6 (never escalates) + H4 (never decays either) - a ceiling and a floor
        Case(recompute, imc, b0, Status.GOOD_STANDING), // rule 7: cured below the Initial limit
        Case(recompute, imc, b1, Status.INITIAL_MARGIN_CALL),
        Case(recompute, imc, b2, Status.INITIAL_MARGIN_CALL),
        Case(recompute, imc, b3, Status.INITIAL_MARGIN_CALL),

        // prev = Maintenance MC: rule 7 hysteresis (no de-escalation to Near Margin) + rule 8 (can promote to Liquidation)
        Case(recompute, mmc, b0, Status.GOOD_STANDING),
        Case(recompute, mmc, b1, Status.MAINTENANCE_MARGIN_CALL),
        Case(recompute, mmc, b2, Status.MAINTENANCE_MARGIN_CALL),
        Case(recompute, mmc, b3, Status.LIQUIDATION),

        // prev = Liquidation: rule 7 hysteresis all the way down to a full cure
        Case(recompute, liq, b0, Status.GOOD_STANDING),
        Case(recompute, liq, b1, Status.LIQUIDATION),
        Case(recompute, liq, b2, Status.LIQUIDATION),
        Case(recompute, liq, b3, Status.LIQUIDATION),
    )

    // --- Link event (rule 5: only produces Good Standing or Initial Margin Call, never overrides MMC/Liquidation) ---
    val linkCases = listOf(
        Case(link, none, b0, Status.GOOD_STANDING),
        Case(link, none, b1, Status.INITIAL_MARGIN_CALL),
        Case(link, none, b2, Status.INITIAL_MARGIN_CALL),
        Case(link, none, b3, Status.INITIAL_MARGIN_CALL),

        Case(link, gs, b0, Status.GOOD_STANDING),
        Case(link, gs, b1, Status.INITIAL_MARGIN_CALL),
        Case(link, gs, b2, Status.INITIAL_MARGIN_CALL),
        Case(link, gs, b3, Status.INITIAL_MARGIN_CALL),

        Case(link, nm, b0, Status.GOOD_STANDING),
        Case(link, nm, b1, Status.INITIAL_MARGIN_CALL),
        Case(link, nm, b2, Status.INITIAL_MARGIN_CALL),
        Case(link, nm, b3, Status.INITIAL_MARGIN_CALL),

        Case(link, imc, b0, Status.GOOD_STANDING),
        Case(link, imc, b1, Status.INITIAL_MARGIN_CALL),
        Case(link, imc, b2, Status.INITIAL_MARGIN_CALL),
        Case(link, imc, b3, Status.INITIAL_MARGIN_CALL),

        // rule 5 bullet: unconditional, even in B0 where an ordinary recompute would cure it (see README, H6)
        Case(link, mmc, b0, Status.MAINTENANCE_MARGIN_CALL),
        Case(link, mmc, b1, Status.MAINTENANCE_MARGIN_CALL),
        Case(link, mmc, b2, Status.MAINTENANCE_MARGIN_CALL),
        Case(link, mmc, b3, Status.MAINTENANCE_MARGIN_CALL),

        Case(link, liq, b0, Status.LIQUIDATION),
        Case(link, liq, b1, Status.LIQUIDATION),
        Case(link, liq, b2, Status.LIQUIDATION),
        Case(link, liq, b3, Status.LIQUIDATION),
    )

    val allCases = recomputeCases + linkCases
    // 6 previous statuses x 4 bands x 2 events = 48 cells, matching result.md §4 exactly.
    check(allCases.size == 48) { "expected 48 cells, found ${allCases.size}" }
    check(recomputeCases.size == 24 && linkCases.size == 24)

    allCases.forEach { c ->
        test("${c.event.label} | prev=${c.prev.label} | ${c.band.label} -> ${c.expected}") {
            CollateralHealth.assess(ca(c.band.requirement, c.prev.status), c.event, prices)
                .status shouldBe c.expected
        }
    }
})
