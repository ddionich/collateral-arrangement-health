package com.dionich.collateral.health.engine

import com.dionich.collateral.health.model.Band
import com.dionich.collateral.health.model.CaId
import com.dionich.collateral.health.model.Event
import com.dionich.collateral.health.model.Limits
import com.dionich.collateral.health.model.Status
import com.dionich.collateral.health.money.Money
import com.dionich.collateral.health.money.Rate

data class HealthAssessment(
    val caId: CaId,
    val status: Status,
    val previousStatus: Status?,
    val event: Event,
    val rateUsed: Rate,
    val collateralValue: Money,
    val limits: Limits,
    val band: Band,
    val statusBeforeHistoryRules: Status,
    val rulesApplied: List<RuleApplied>,
) {
    val changed: Boolean get() = status != previousStatus
}
