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

    @Test
    fun `Ltv equality does not depend on the BigDecimal scale of its input`() {
        // ofPercent(Int) and ofPercent(BigDecimal) can produce the same ratio at different
        // scales ("0.65" vs "0.650"); equals/hashCode must agree with compareTo regardless.
        val fromInt = Ltv.ofPercent(65)
        val fromDecimal = Ltv.ofPercent("65.0".toBigDecimal())
        assertEquals(0, fromInt.compareTo(fromDecimal), "same economic value per compareTo")
        assertEquals(fromInt, fromDecimal)
        assertEquals(fromInt.hashCode(), fromDecimal.hashCode())
    }

    @Test
    fun `Rate equality ignores BigDecimal scale, same as Money`() {
        val whole = Rate("30000".toBigDecimal(), btc, usdc)
        val padded = Rate("30000.00".toBigDecimal(), btc, usdc)
        assertEquals(whole, padded, "same rate, different BigDecimal scale")
    }
}
