package com.dionich.collateral.health.model

// the order of this enum is important, as it is used to determine the severity of the status
//TODO: Create a test to make sure the order will not be changed in the future
enum class Status {
    GOOD_STANDING,
    NEAR_MARGIN,
    INITIAL_MARGIN_CALL,
    MAINTENANCE_MARGIN_CALL,
    LIQUIDATION;

    companion object {
        val CALLED = setOf(MAINTENANCE_MARGIN_CALL, LIQUIDATION)
    }
}
