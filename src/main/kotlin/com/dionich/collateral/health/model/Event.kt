package com.dionich.collateral.health.model

// sealed so that we can exhaustively match on it in when statements, if new event types are added, the compiler will warn us to handle them
sealed interface Event {
    val label: String

    data object Link : Event { override val label = "link" }

    data class Recompute(val reason: Reason = Reason.UNSPECIFIED) : Event {
        override val label get() = "recompute"
    }
}

enum class Reason { PRICE_MOVE, BALANCE_CHANGE, REPAYMENT, QUERY, UNSPECIFIED }
