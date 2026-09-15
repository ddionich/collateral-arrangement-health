package com.dionich.collateral.health.money

import java.math.BigDecimal

@JvmInline
//private constructor so only the companion object can create instances of Ltv using ofPercent() factory method
value class Ltv private constructor(val ratio: BigDecimal) : Comparable<Ltv> {

    override fun compareTo(other: Ltv) = ratio.compareTo(other.ratio)
    override fun toString() = "${ratio.movePointRight(2).toPlainString()}%"

    companion object {
        fun ofPercent(percent: BigDecimal): Ltv {
            require(percent.signum() >= 0) { "LTV must not be negative: $percent%" }
            return Ltv(percent.movePointLeft(2).stripTrailingZeros())
        }
        fun ofPercent(percent: Int): Ltv = ofPercent(percent.toBigDecimal())
    }
}

data class LtvSet(val initial: Ltv, val maintenance: Ltv, val liquidation: Ltv) {
    init {
        require(initial <= maintenance) { "Initial LTV $initial must be <= Maintenance LTV $maintenance" }
        require(maintenance <= liquidation) { "Maintenance LTV $maintenance must be <= Liquidation LTV $liquidation" }
    }
}
