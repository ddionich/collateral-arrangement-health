package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.Band
import com.dionich.collateral.health.model.CollateralArrangement
import com.dionich.collateral.health.model.Event
import com.dionich.collateral.health.model.Limits
import com.dionich.collateral.health.model.Status
import com.dionich.collateral.health.money.Asset
import com.dionich.collateral.health.money.Money
import com.dionich.collateral.health.money.Rate
import java.math.BigDecimal

object CollateralHealth {

    fun assess(ca: CollateralArrangement, event: Event, prices: PriceSource): HealthAssessment {
        val rate = rateFor(ca.collateral.asset, ca.requirement.asset, prices)
        val collateralValue = ca.collateral * rate
        val limits = Limits.from(collateralValue, ca.ltvs)
        val band: Band = limits.locate(ca.requirement)
        val candidate: Status = candidateFor(event, band)
        val status: Status = applyHistory(ca.currentStatus, candidate, band, event)

        val baseReason: RuleApplied = if (event is Event.Link) RuleApplied.LINK_CLASSIFICATION else RuleApplied.BASE_BAND
        val historyReason: RuleApplied? =
            if (status == candidate) null
            else historyReasonFor(ca.currentStatus, candidate, status, event)

        return HealthAssessment(
            caId = ca.id,
            status = status,
            previousStatus = ca.currentStatus,
            event = event,
            rateUsed = rate,
            collateralValue = collateralValue,
            limits = limits,
            band = band,
            statusBeforeHistoryRules = candidate,
            rulesApplied = listOfNotNull(baseReason, historyReason),
        )
    }

    private fun rateFor(from: Asset, into: Asset, prices: PriceSource): Rate =
        if (from == into) Rate(BigDecimal.ONE, into, into) else prices.rate(from, into)

    /**
     * Determines the candidate status based on the event and the band. Using the rules defined in the documentation.
     * 1. requirement below the Initial limit → Good Standing
     * 2. requirement at or above Initial and below Maintenance → Near Margin
     * 3. requirement at or above Maintenance and below Liquidation → Maintenance Margin Call
     * 4. requirement at or above Liquidation → Liquidation
     * Precedence. The three LTVs are allowed to be equal to one another. When a requirement lands
     * exactly on a shared threshold, the more severe status wins: Liquidation takes precedence over
     * Maintenance, and Maintenance over Initial.
     * 5. On a link event: the only statuses a link may produce are Good Standing or Initial
     * Margin Call. If the requirement lands at or above the Initial limit, the status becomes
     * Initial Margin Call (regardless of which higher limit is crossed); otherwise it is Good
     * Standing.
     */
    private fun candidateFor(event: Event, band: Band): Status = when (event) {
        // Rule 5
        //TODO: Check if I can reverse this if statement to make it more readable
        is Event.Link -> if (band == Band.BELOW_INITIAL) Status.GOOD_STANDING else Status.INITIAL_MARGIN_CALL
        // Rules 1-4
        is Event.Recompute -> when (band) {
            Band.BELOW_INITIAL           -> Status.GOOD_STANDING
            Band.AT_OR_ABOVE_INITIAL     -> Status.NEAR_MARGIN
            Band.AT_OR_ABOVE_MAINTENANCE -> Status.MAINTENANCE_MARGIN_CALL
            Band.AT_OR_ABOVE_LIQUIDATION -> Status.LIQUIDATION
        }
    }


    private fun applyHistory(prev: Status?, candidate: Status, band: Band, event: Event): Status = when {
        //However, a link event must never override a preexisting Maintenance
        //Margin Call or Liquidation. If the CA is already in one of those two statuses, a
        //link leaves it unchanged.
        event is Event.Link && prev in Status.CALLED   -> prev!!                    // 5a, rule 5 bullet

        //1. requirement below the Initial limit → Good Standing
        //7. A CA in Maintenance Margin Call or Liquidation does not return to Good Standing
        //until the requirement lands below the Initial limit.
        band == Band.BELOW_INITIAL                     -> Status.GOOD_STANDING      // 5b, rules 1 & 7

        //6. A CA in Initial Margin Call cannot be promoted to Maintenance Margin Call or
        //Liquidation.
        prev == Status.INITIAL_MARGIN_CALL             -> Status.INITIAL_MARGIN_CALL// 5c, rule 6 + H4

        //7. A CA in Maintenance Margin Call or Liquidation does not return to Good Standing
        //until the requirement lands below the Initial limit.
        //8. A Maintenance Margin Call can be promoted to Liquidation.
        prev in Status.CALLED                          -> maxOf(prev!!, candidate)  // 5d, rule 7 + rule 8
        else                                           -> candidate                 // 5e
    }

    private fun historyReasonFor(prev: Status?, candidate: Status, result: Status, event: Event): RuleApplied = when {
        event is Event.Link && prev in Status.CALLED -> RuleApplied.LINK_CANNOT_OVERRIDE_CALLED
        result == Status.GOOD_STANDING               -> RuleApplied.CURED_BELOW_INITIAL_LIMIT
        prev == Status.INITIAL_MARGIN_CALL           ->
            if (candidate > Status.INITIAL_MARGIN_CALL) RuleApplied.INITIAL_CALL_CANNOT_ESCALATE
            else RuleApplied.INITIAL_CALL_HELD_UNTIL_CURED
        result == Status.LIQUIDATION && prev == Status.MAINTENANCE_MARGIN_CALL -> RuleApplied.PROMOTED_TO_LIQUIDATION
        else -> RuleApplied.CALLED_STATUS_HELD_UNTIL_CURED
    }
}
