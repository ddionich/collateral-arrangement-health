package com.dionich.collateral.health.model

import com.dionich.collateral.health.money.LtvSet
import com.dionich.collateral.health.money.Money

@JvmInline value class CaId(val value: String)

data class CollateralArrangement(
    val id: CaId,
    val collateral: Money,
    val requirement: Money,
    val ltvs: LtvSet,
    val currentStatus: Status? = null,
)
