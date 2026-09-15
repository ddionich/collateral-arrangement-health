package com.dionich.collateral.health.engine

enum class RuleApplied(val spec: String, val description: String) {
    BASE_BAND("rules 1-4", "classified by where the requirement lands against the limits"),
    LINK_CLASSIFICATION("rule 5", "a link may only produce Good Standing or Initial Margin Call"),
    LINK_CANNOT_OVERRIDE_CALLED("rule 5", "a link never overrides a preexisting Maintenance Margin Call or Liquidation"),
    CURED_BELOW_INITIAL_LIMIT("rule 7", "the requirement cleared the Initial limit, so the CA is cured"),
    INITIAL_CALL_CANNOT_ESCALATE("rule 6", "an Initial Margin Call is never promoted to Maintenance or Liquidation"),
    INITIAL_CALL_HELD_UNTIL_CURED("assumption H4", "an outstanding call is not downgraded to a warning by a price tick"),
    CALLED_STATUS_HELD_UNTIL_CURED("rule 7 / assumption H2", "a called CA does not de-escalate before a full cure"),
    PROMOTED_TO_LIQUIDATION("rule 8", "a Maintenance Margin Call was promoted to Liquidation"),
}
