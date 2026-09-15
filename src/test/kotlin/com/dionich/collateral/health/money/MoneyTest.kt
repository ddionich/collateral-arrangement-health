package com.dionich.collateral.health.money

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MoneyTest : FunSpec({
    val usdc = Asset("USDC"); val btc = Asset("BTC")

    test("equality ignores BigDecimal scale") {
        Money("39000".toBigDecimal(), usdc) shouldBe Money("39000.00".toBigDecimal(), usdc)
        Money("39000".toBigDecimal(), usdc).hashCode() shouldBe Money("39000.00".toBigDecimal(), usdc).hashCode()
    }
    test("comparing across assets is rejected") {
        shouldThrow<IllegalArgumentException> {
            Money("1".toBigDecimal(), usdc) < Money("1".toBigDecimal(), btc)
        }
    }
    test("negative amounts are rejected") {
        shouldThrow<IllegalArgumentException> { Money("-1".toBigDecimal(), usdc) }
    }
    test("unordered LTVs are rejected") {
        shouldThrow<IllegalArgumentException> {
            LtvSet(Ltv.ofPercent(80), Ltv.ofPercent(65), Ltv.ofPercent(50))
        }
    }
    test("percent converts to an exact ratio") {
        Ltv.ofPercent(65).ratio.compareTo("0.65".toBigDecimal()) shouldBe 0
    }
})
