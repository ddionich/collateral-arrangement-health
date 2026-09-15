package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.*
import com.dionich.collateral.health.money.Asset
import com.dionich.collateral.health.money.Rate
import java.math.BigDecimal

object CollateralHealth {

    /**
     * Assesses a collateral arrangement's health via a five-step pipeline: value the
     * collateral, derive the Initial/Maintenance/Liquidation limits, locate the band the
     * requirement falls in, classify a candidate status from the event and band (rules 1-5),
     * then apply the history rules (rules 6-8) against the arrangement's current status.
     *
     * @param ca the collateral arrangement to assess, including its current status, if any.
     * @param event the trigger for this assessment - a `Link` or an ordinary `Recompute`.
     * @param prices source of exchange rates for valuing the collateral in the requirement's
     *   asset; not consulted when both are already the same asset (see [rateFor]).
     * @return the resulting [HealthAssessment], including the new status, the band and limits
     *   it was derived from, and the [RuleApplied] citations that justify it.
     */
    fun assess(ca: CollateralArrangement, event: Event, prices: PriceSource): HealthAssessment {
        val rate = rateFor(ca.collateral.asset, ca.requirement.asset, prices)
        val collateralValue = ca.collateral * rate
        val limits = Limits.from(collateralValue, ca.ltvs)
        val band: Band = limits.locate(ca.requirement)
        val candidateStatus: Status = candidateFor(event, band)
        val historyOutcome = applyHistory(ca.currentStatus, candidateStatus, band, event)

        val baseReason: RuleApplied = if (event is Event.Link) RuleApplied.LINK_CLASSIFICATION else RuleApplied.BASE_BAND

        return HealthAssessment(
            caId = ca.id,
            status = historyOutcome.status,
            previousStatus = ca.currentStatus,
            event = event,
            rateUsed = rate,
            collateralValue = collateralValue,
            limits = limits,
            band = band,
            statusBeforeHistoryRules = candidateStatus,
            rulesApplied = listOfNotNull(baseReason, historyOutcome.reason),
        )
    }

    /**
     * Resolves the exchange rate used to value the collateral in the requirement's asset.
     *
     * @param from the collateral's asset.
     * @param into the requirement's asset.
     * @param prices source of exchange rates, consulted only when [from] and [into] differ.
     * @return a 1:1 [Rate] when [from] equals [into] (assumption H9), otherwise `prices.rate(from, into)`.
     */
    private fun rateFor(from: Asset, into: Asset, prices: PriceSource): Rate =
        if (from == into) Rate(BigDecimal.ONE, into, into) else prices.rate(from, into)

    /**
     * Determines the candidate status from the event and the band alone, with no regard for
     * history. This is the base classification (rules 1-5); [applyHistory] may still override
     * it based on the arrangement's previous status.
     *
     * 1. requirement below the Initial limit → Good Standing
     * 2. requirement at or above Initial and below Maintenance → Near Margin
     * 3. requirement at or above Maintenance and below Liquidation → Maintenance Margin Call
     * 4. requirement at or above Liquidation → Liquidation
     *
     * Precedence: the three LTVs are allowed to be equal to one another. When a requirement
     * lands exactly on a shared threshold, the more severe status wins - Liquidation takes
     * precedence over Maintenance, and Maintenance over Initial.
     *
     * 5. On a link event: the only statuses a link may produce are Good Standing or Initial
     * Margin Call. If the requirement lands at or above the Initial limit, the status becomes
     * Initial Margin Call (regardless of which higher limit is crossed); otherwise it is Good
     * Standing.
     *
     * @param event the `Link` or `Recompute` that triggered this assessment.
     * @param band where the requirement falls relative to the Initial/Maintenance/Liquidation limits.
     * @return the candidate [Status], per rules 1-5.
     */
    private fun candidateFor(event: Event, band: Band): Status = when (event) {
        // Rule 5
        is Event.Link -> if (band >= Band.AT_OR_ABOVE_INITIAL) Status.INITIAL_MARGIN_CALL else Status.GOOD_STANDING
        // Rules 1-4
        is Event.Recompute -> when (band) {
            Band.BELOW_INITIAL           -> Status.GOOD_STANDING
            Band.AT_OR_ABOVE_INITIAL     -> Status.NEAR_MARGIN
            Band.AT_OR_ABOVE_MAINTENANCE -> Status.MAINTENANCE_MARGIN_CALL
            Band.AT_OR_ABOVE_LIQUIDATION -> Status.LIQUIDATION
        }
    }


    /**
     * @property reason null when [candidateFor]'s base classification already explains
     *   [status] on its own, with no history rule involved.
     */
    private data class HistoryOutcome(val status: Status, val reason: RuleApplied?)

    /**
     * Applies the history rules (6-8) on top of the base candidate from [candidateFor],
     * resolving both the final status and, when a history rule is the reason for it, the
     * [RuleApplied] citation to go with it:
     *
     * - 5a: a link never overrides a preexisting Maintenance Margin Call or Liquidation (rule 5).
     * - 5b: a requirement below the Initial limit is always Good Standing (rules 1 & 7).
     * - 5c: an Initial Margin Call is both a ceiling and a floor - it cannot be promoted to a
     *   worse status (rule 6), and it does not decay back to Near Margin either (assumption H4).
     * - 5d: a Maintenance Margin Call or Liquidation does not de-escalate until cured (rule 7),
     *   though a Maintenance Margin Call can still be promoted to Liquidation (rule 8).
     * - 5e: no history rule applies; the candidate status stands as-is.
     *
     * @param previousStatus the arrangement's status before this assessment, or `null` if it
     *   has never been assessed before.
     * @param candidateStatus the base status from [candidateFor], before history is considered.
     * @param band where the requirement falls relative to the Initial/Maintenance/Liquidation limits.
     * @param event the `Link` or `Recompute` that triggered this assessment.
     * @return a [HistoryOutcome] with the final status and, if applicable, the rule that produced it.
     */
    private fun applyHistory(previousStatus: Status?, candidateStatus: Status, band: Band, event: Event): HistoryOutcome = when {
        //However, a link event must never override a preexisting Maintenance
        //Margin Call or Liquidation. If the CA is already in one of those two statuses, a
        //link leaves it unchanged.
        event is Event.Link && previousStatus in Status.CALLED ->
            HistoryOutcome(previousStatus!!, RuleApplied.LINK_CANNOT_OVERRIDE_CALLED)// 5a, rule 5 bullet

        //1. requirement below the Initial limit → Good Standing
        //7. A CA in Maintenance Margin Call or Liquidation does not return to Good Standing
        //until the requirement lands below the Initial limit.
        band == Band.BELOW_INITIAL ->
            HistoryOutcome(
                status = Status.GOOD_STANDING,
                reason = if (previousStatus != null && previousStatus != Status.GOOD_STANDING)
                    RuleApplied.CURED_BELOW_INITIAL_LIMIT
                else null,
            )

        //6. A CA in Initial Margin Call cannot be promoted to Maintenance Margin Call or
        //Liquidation.
        previousStatus == Status.INITIAL_MARGIN_CALL -> HistoryOutcome(
            Status.INITIAL_MARGIN_CALL,
            if (candidateStatus > Status.INITIAL_MARGIN_CALL) RuleApplied.INITIAL_CALL_CANNOT_ESCALATE
            else RuleApplied.INITIAL_CALL_HELD_UNTIL_CURED,
        )// 5c, rule 6 + H4

        //7. A CA in Maintenance Margin Call or Liquidation does not return to Good Standing
        //until the requirement lands below the Initial limit.
        //8. A Maintenance Margin Call can be promoted to Liquidation.
        previousStatus in Status.CALLED -> {
            val status = maxOf(previousStatus!!, candidateStatus)
            val reason = if (status == Status.LIQUIDATION && previousStatus == Status.MAINTENANCE_MARGIN_CALL)
                RuleApplied.PROMOTED_TO_LIQUIDATION
            else RuleApplied.CALLED_STATUS_HELD_UNTIL_CURED
            HistoryOutcome(status, reason)// 5d, rule 7 + rule 8
        }

        else -> HistoryOutcome(candidateStatus, null)// 5e
    }

}
