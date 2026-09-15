package com.dionich.collateral.health.engine

import com.dionich.collateral.health.money.Asset
import com.dionich.collateral.health.money.Rate

fun interface PriceSource {
    fun rate(base: Asset, quote: Asset): Rate
}
