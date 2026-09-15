package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.*
import com.dionich.collateral.health.money.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * HealthAssessment.rulesApplied is the explainability trail: the README documents it as
 * mapping 1:1 onto RuleApplied. One test per RuleApplied constant, asserting the exact
 * citation `assess()` returns for a transition that triggers it - not just the resulting
 * status (that's already TransitionMatrixTest's job).
 */
class RuleAppliedTest {

    private val btc = Asset("BTC")
    private val usdc = Asset("USDC")
    private val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }
    private val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80))

    private fun ca(requirement: String, prev: Status?) = CollateralArrangement(
        id = CaId("ca-rules"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money(requirement.toBigDecimal(), usdc),
        ltvs = ltvs,
        currentStatus = prev,
    )

    @Test
    fun `BASE_BAND - an ordinary recompute with no relevant history cites only the base band`() {
        val a = CollateralHealth.assess(ca("39000", null), Event.Recompute(), prices)
        assertEquals(listOf(RuleApplied.BASE_BAND), a.rulesApplied)
    }

    @Test
    fun `LINK_CLASSIFICATION - a link with no relevant history cites only the link classification`() {
        val a = CollateralHealth.assess(ca("39000", null), Event.Link, prices)
        assertEquals(listOf(RuleApplied.LINK_CLASSIFICATION), a.rulesApplied)
    }

    @Test
    fun `LINK_CANNOT_OVERRIDE_CALLED - a link on a called CA cites both the link and the override-block reason`() {
        val a = CollateralHealth.assess(ca("39000", Status.MAINTENANCE_MARGIN_CALL), Event.Link, prices)
        assertEquals(Status.MAINTENANCE_MARGIN_CALL, a.status)
        assertContains(a.rulesApplied, RuleApplied.LINK_CANNOT_OVERRIDE_CALLED)
    }

    @Test
    fun `CURED_BELOW_INITIAL_LIMIT - a called CA cured by a recompute cites the cure rule`() {
        val a = CollateralHealth.assess(ca("29999.99", Status.LIQUIDATION), Event.Recompute(), prices)
        assertEquals(Status.GOOD_STANDING, a.status)
        assertContains(a.rulesApplied, RuleApplied.CURED_BELOW_INITIAL_LIMIT)
    }

    @Test
    fun `INITIAL_CALL_CANNOT_ESCALATE - an Initial Margin Call held despite a worse band cites rule 6`() {
        val a = CollateralHealth.assess(ca("48000", Status.INITIAL_MARGIN_CALL), Event.Recompute(), prices)
        assertEquals(Status.INITIAL_MARGIN_CALL, a.status)
        assertContains(a.rulesApplied, RuleApplied.INITIAL_CALL_CANNOT_ESCALATE)
    }

    @Test
    fun `INITIAL_CALL_HELD_UNTIL_CURED - an Initial Margin Call not decaying to Near Margin cites H4`() {
        val a = CollateralHealth.assess(ca("30000", Status.INITIAL_MARGIN_CALL), Event.Recompute(), prices)
        assertEquals(Status.INITIAL_MARGIN_CALL, a.status)
        assertContains(a.rulesApplied, RuleApplied.INITIAL_CALL_HELD_UNTIL_CURED)
    }

    @Test
    fun `CALLED_STATUS_HELD_UNTIL_CURED - hysteresis holding a Maintenance Margin Call cites rule 7`() {
        val a = CollateralHealth.assess(ca("30000", Status.MAINTENANCE_MARGIN_CALL), Event.Recompute(), prices)
        assertEquals(Status.MAINTENANCE_MARGIN_CALL, a.status)
        assertContains(a.rulesApplied, RuleApplied.CALLED_STATUS_HELD_UNTIL_CURED)
    }

    @Test
    fun `PROMOTED_TO_LIQUIDATION - a Maintenance Margin Call promoted to Liquidation cites rule 8`() {
        val a = CollateralHealth.assess(ca("48000", Status.MAINTENANCE_MARGIN_CALL), Event.Recompute(), prices)
        assertEquals(Status.LIQUIDATION, a.status)
        assertContains(a.rulesApplied, RuleApplied.PROMOTED_TO_LIQUIDATION)
    }
}
