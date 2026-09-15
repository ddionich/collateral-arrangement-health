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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exactly the four properties from plan-kotlin.md §7 - no more. The transition
 * matrix already gives exhaustive coverage of the decision space; these are a
 * cross-cutting sanity net over the whole input space, not a second source of
 * documentation.
 *
 * No property-testing library is used (kotest-property was dropped along with Kotest),
 * so each property is checked over a fixed number of pseudo-random samples drawn from
 * a seeded Random - deterministic across runs, without pulling in a new dependency.
 */
class HealthPropertyTest {

    private val btc = Asset("BTC")
    private val usdc = Asset("USDC")
    private val prices = PriceSource { _, _ -> Rate("30000".toBigDecimal(), btc, usdc) }
    private val ltvs = LtvSet(Ltv.ofPercent(50), Ltv.ofPercent(65), Ltv.ofPercent(80))
    private val statuses = Status.entries.toList()
    private val samples = 500

    private fun ca(requirement: Long, prev: Status?) = CollateralArrangement(
        id = CaId("ca-property"),
        collateral = Money("2".toBigDecimal(), btc),
        requirement = Money(requirement.toBigDecimal(), usdc),
        ltvs = ltvs,
        currentStatus = prev,
    )

    private fun Random.nextPrevOrNull(): Status? = if (nextBoolean()) null else statuses[nextInt(statuses.size)]
    private fun Random.nextEvent(): Event = if (nextBoolean()) Event.Link else Event.Recompute(Reason.PRICE_MOVE)
    private fun Random.nextRequirement(): Long = nextLong(0L, 60_000L)

    @Test
    fun `P1 raising the requirement, everything else fixed, never yields a less severe status`() {
        val random = Random(1)
        repeat(samples) {
            val prev = random.nextPrevOrNull()
            val event = random.nextEvent()
            val base = random.nextRequirement()
            val delta = random.nextRequirement()
            val lower = CollateralHealth.assess(ca(base, prev), event, prices).status
            val higher = CollateralHealth.assess(ca(base + delta, prev), event, prices).status
            assertTrue(higher >= lower)
        }
    }

    @Test
    fun `P2 the result is never less severe than the previous status, unless the requirement clears the Initial limit (a full cure)`() {
        val random = Random(2)
        repeat(samples) {
            val prev = statuses[random.nextInt(statuses.size)]
            val event = random.nextEvent()
            val requirement = random.nextRequirement()
            val a = CollateralHealth.assess(ca(requirement, prev), event, prices)
            if (a.band != Band.BELOW_INITIAL) {
                assertTrue(a.status >= prev)
            }
        }
    }

    @Test
    fun `P3 a link only produces Good Standing or Initial Margin Call, or leaves a preexisting Maintenance Margin Call or Liquidation untouched`() {
        val random = Random(3)
        repeat(samples) {
            val prev = random.nextPrevOrNull()
            val requirement = random.nextRequirement()
            val result = CollateralHealth.assess(ca(requirement, prev), Event.Link, prices).status
            val leftCalledStatusIntact = prev in Status.CALLED && result == prev
            assertTrue(result == Status.GOOD_STANDING || result == Status.INITIAL_MARGIN_CALL || leftCalledStatusIntact)
        }
    }

    @Test
    fun `P4 with all three LTVs equal, the base classification for a recompute can only be Good Standing or Liquidation`() {
        // Asserted against statusBeforeHistoryRules, not the final status: this property is
        // about the structural precedence rule (H5), which lives entirely in the base
        // classification step. The Initial Margin Call ceiling (rule 6) is a separate,
        // history-dependent concern, already covered exhaustively by TransitionMatrixTest -
        // it can hold the final status at Initial Margin Call even when the band collapses
        // to Good Standing/Liquidation only, so it must not be conflated with this property.
        val random = Random(4)
        repeat(samples) {
            val prev = random.nextPrevOrNull()
            val requirement = random.nextRequirement()
            val percent = random.nextInt(1, 100)
            val equalLtv = Ltv.ofPercent(percent)
            val equalCa = CollateralArrangement(
                id = CaId("ca-equal-ltv"),
                collateral = Money("2".toBigDecimal(), btc),
                requirement = Money(requirement.toBigDecimal(), usdc),
                ltvs = LtvSet(equalLtv, equalLtv, equalLtv),
                currentStatus = prev,
            )
            val base = CollateralHealth.assess(equalCa, Event.Recompute(), prices).statusBeforeHistoryRules
            assertTrue(base == Status.GOOD_STANDING || base == Status.LIQUIDATION)
        }
    }
}
