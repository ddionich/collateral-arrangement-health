package com.dionich.collateral.health.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    private val usdc = Asset("USDC")
    private val btc = Asset("BTC")

    @Test
    fun `equality ignores BigDecimal scale`() {
        assertEquals(Money("39000".toBigDecimal(), usdc), Money("39000.00".toBigDecimal(), usdc))
        assertEquals(
            Money("39000".toBigDecimal(), usdc).hashCode(),
            Money("39000.00".toBigDecimal(), usdc).hashCode(),
        )
    }

    @Test
    fun `comparing across assets is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Money("1".toBigDecimal(), usdc) < Money("1".toBigDecimal(), btc)
        }
    }

    @Test
    fun `negative amounts are rejected`() {
        assertFailsWith<IllegalArgumentException> { Money("-1".toBigDecimal(), usdc) }
    }

    @Test
    fun `unordered LTVs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LtvSet(Ltv.ofPercent(80), Ltv.ofPercent(65), Ltv.ofPercent(50))
        }
    }

    @Test
    fun `percent converts to an exact ratio`() {
        assertEquals(0, Ltv.ofPercent(65).ratio.compareTo("0.65".toBigDecimal()))
    }
}
