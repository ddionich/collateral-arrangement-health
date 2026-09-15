package com.dionich.collateral.health.model

import com.dionich.collateral.health.money.LtvSet
import com.dionich.collateral.health.money.Money

enum class Band { BELOW_INITIAL, AT_OR_ABOVE_INITIAL, AT_OR_ABOVE_MAINTENANCE, AT_OR_ABOVE_LIQUIDATION }

data class Limits(val initial: Money, val maintenance: Money, val liquidation: Money) {

    fun locate(requirement: Money): Band = when {
        requirement.isZero         -> Band.BELOW_INITIAL
        requirement >= liquidation -> Band.AT_OR_ABOVE_LIQUIDATION
        requirement >= maintenance -> Band.AT_OR_ABOVE_MAINTENANCE
        requirement >= initial     -> Band.AT_OR_ABOVE_INITIAL
        else                       -> Band.BELOW_INITIAL
    }

    companion object {
        fun from(collateralValue: Money, ltvs: LtvSet) = Limits(
            initial     = collateralValue * ltvs.initial,
            maintenance = collateralValue * ltvs.maintenance,
            liquidation = collateralValue * ltvs.liquidation,
        )
    }
}
