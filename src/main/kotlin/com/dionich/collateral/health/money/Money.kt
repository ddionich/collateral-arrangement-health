package com.dionich.collateral.health.money

import java.math.BigDecimal

// I'm using inline classes to represent assets and money because they are lightweight and efficient, and they provide type safety.
// when compiling to JVM bytecode, inline classes are represented as their underlying type
// (in this case, String for Asset and BigDecimal for Money), which means they have no runtime overhead compared to using the underlying type directly.
@JvmInline
value class Asset(val symbol: String) {
    init { require(symbol.isNotBlank()) { "asset symbol must not be blank" } }
    override fun toString() = symbol
}

class Money(val amount: BigDecimal, val asset: Asset) : Comparable<Money> {

    init { require(amount.signum() >= 0) { "amount must not be negative: $amount $asset" } }

    val isZero: Boolean get() = amount.signum() == 0

    // we use operator functions so we can use the standard operators like +, -, *, etc. with Money objects
    operator fun times(ltv: Ltv): Money = Money(amount.multiply(ltv.ratio), asset)

    operator fun times(rate: Rate): Money {
        require(asset == rate.base) { "cannot value $asset with a ${rate.base}/${rate.quote} rate" }
        return Money(amount.multiply(rate.value), rate.quote)
    }

    override fun compareTo(other: Money): Int {
        require(asset == other.asset) { "cannot compare $asset against ${other.asset}" }
        return amount.compareTo(other.amount)
    }

    override fun equals(other: Any?) =
        other is Money && asset == other.asset && amount.compareTo(other.amount) == 0

    override fun hashCode() = 31 * asset.hashCode() + amount.stripTrailingZeros().hashCode()

    override fun toString() = "$asset [${amount.toPlainString()}]"
}

// this is used like Money(2, BTC) * Rate(30000, BTC, USDC), which will give you Money(60000, USDC) only if the base asset of the rate matches the asset of the money
// so we avoid using Money(2, BTC) * Rate(30000, ETH, USDC), which would be nonsensical, and it can provoke a silent error if the user (or the future code) is not careful,
// so we enforce that the base asset of the rate must match the asset of the money
class Rate(val value: BigDecimal, val base: Asset, val quote: Asset) {
    init { require(value.signum() > 0) { "rate must be positive" } }

    // Not a data class: a data class's generated equals would use BigDecimal.equals on
    // `value`, which is scale-sensitive - the same reason Money hand-writes its own equals.
    override fun equals(other: Any?) =
        other is Rate && base == other.base && quote == other.quote && value.compareTo(other.value) == 0

    override fun hashCode(): Int {
        var result = base.hashCode()
        result = 31 * result + quote.hashCode()
        result = 31 * result + value.stripTrailingZeros().hashCode()
        return result
    }

    override fun toString() = "$value $base/$quote"
}
