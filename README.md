# collateral-health

A pure Kotlin library that classifies the health of a Collateral Arrangement (CA) into one
of five statuses, given its collateral, its requirement, its three LTVs, the event that
triggered the recompute, and (optionally) its previous status.

## What this is

No persistence, no HTTP, no CLI, no framework — a library with a small public API meant to
be called from HTTP controllers and background jobs, exactly as the brief asks for. The
whole domain is five files and ~120 lines under `src/main/kotlin/collateral/health/`; the
rest is tests.

```bash
./gradlew test
```

That's the only command needed, from a clean clone, with no network access beyond the first
dependency download.

## The public API

```kotlin
fun interface PriceSource {
    fun rate(base: Asset, quote: Asset): Rate   // throws if no price is available
}

object CollateralHealth {
    fun assess(ca: CollateralArrangement, event: Event, prices: PriceSource): HealthAssessment
}
```

`assess` is a pure five-step pipeline (valuate → derive limits → locate band → candidate
status from the event → apply history rules) and returns an enriched `HealthAssessment`,
not a bare `Status`. The brief asks explicitly for code whose reasoning is "legible to
consumers"; a naked enum throws that signal away; `HealthAssessment` carries the limits, the
band the requirement landed in, the pre-history candidate, and the list of named rules that
were applied. Both consumers the brief names fall out of it directly:

```kotlin
// HTTP controller — the whole assessment serializes as-is
fun getHealth(id: CaId): HealthResponse {
    val ca = repo.load(id)
    val assessment = CollateralHealth.assess(ca, Event.Recompute(Reason.QUERY), prices)
    return assessment.toResponse()   // status, limits, band and rulesApplied, all to the client
}

// Background job — decide whether there's anything to do, and leave a trail of why
fun onPriceTick(ca: CollateralArrangement) {
    val assessment = CollateralHealth.assess(ca, Event.Recompute(Reason.PRICE_MOVE), prices)
    if (assessment.changed) {
        repo.updateStatus(ca.id, assessment.status)
        log.info("CA {} {} -> {} ({})", ca.id, assessment.previousStatus,
                 assessment.status, assessment.rulesApplied)
    }
}
```

One design point worth a sentence here rather than its own section: the PDF's precedence
rule (equal LTVs, the more severe status wins on a shared threshold) needs no special-case
code. `Limits.locate` evaluates the ladder top-down — liquidation, then maintenance, then
initial — so when two limits coincide numerically the less severe band is simply empty and
the requirement falls straight through to the more severe one. Precedence is structural, not
a branch; `PrecedenceTest` pins it with three cases.

## Assumptions & ambiguities

This is the section that matters most. The brief warns its own rules have gaps and places
where the literal wording and the likely intent diverge, and asks for decisions to be
written down with their rationale rather than debated forever. Each row below cites the PDF
rule(s) it reads, and maps 1:1 onto a `RuleApplied` constant in the code — that
correspondence is deliberate, so a reviewer can jump from this table straight to the line
that implements it.

| # | Ambiguity | Reading chosen | Why | Tested in |
|---|---|---|---|---|
| H1 | Rules 1–4 never produce `Initial Margin Call`; rule 5 (`link`) never produces `Near Margin`. Are these the same band with two outcomes, or is `Near Margin` dead code? | They are **the same band `[Initial, Maintenance)`, two different triggers**. An ordinary recompute in that band is a warning (`Near Margin`); a `link` landing there is an actual call (`Initial Margin Call`). Neither status is dead — each is reachable only from its own event. | This is the central asymmetry the spec is built around, not a gap. Missing it either turns `Near Margin` into dead code or collapses the two statuses into one. | `TransitionMatrixTest` (band B1 under both events), `RuleApplied.BASE_BAND` / `LINK_CLASSIFICATION` |
| H2 | Rule 7 says an `MMC`/`Liquidation` "does not return to Good Standing until the requirement lands below the Initial limit." Does that *only* name the cure threshold, or does it also mean the status can't de-escalate to `Near Margin` on a partial recovery? | **Hysteresis**: while `requirement >= Initial limit`, a called CA (`MMC` or `Liquidation`) does not de-escalate at all — not even to `Near Margin` — until it fully cures. `Liquidation` does not fall back to `MMC` on a partial recovery either. | Both readings are grammatically defensible. I chose hysteresis on domain grounds: without it, a CA oscillating around the Maintenance threshold would flap between `MMC` and `Near Margin` on every price tick, generating notifications and operational noise on every bounce — exactly the failure mode a cure band exists to prevent. | `TransitionMatrixTest` (prev=MMC/Liquidation × bands B1–B2), `RuleApplied.CALLED_STATUS_HELD_UNTIL_CURED` |
| H3 | Rule 6 ("an `Initial Margin Call` cannot be promoted") combined with rule 5 ("`link` → `Initial Margin Call` regardless of which higher limit is crossed") means a CA linked while massively under-collateralized gets stuck in `Initial Margin Call` forever, even if the price keeps falling past the Liquidation limit. Bug or intentional? | Implemented **literally**. I believe it's intentional: rule 8 ("a `Maintenance Margin Call` *can* be promoted to Liquidation") only makes sense as a rule if it exists to contrast with rule 6 — the author is drawing that distinction on purpose. | Fidelity to the spec either way: if intentional, it's implemented correctly; if it was the planted bug, it's now documented rather than silently patched. See "A risk I'd raise before shipping" below. | `TransitionMatrixTest` (prev=Initial MC × all bands), `RuleApplied.INITIAL_CALL_CANNOT_ESCALATE` |
| H4 | Rule 6 only forbids *promotion* out of `Initial Margin Call`; base rule 2 assigns `Near Margin` to the same band unconditionally. Read completely literally, does an `Initial Margin Call` decay to `Near Margin` on the very next recompute? | **No — it persists.** An `Initial Margin Call` holds until the requirement clears the Initial limit; it is a floor as well as a ceiling. | This is the one genuine deviation from the literal text, and I've kept it labelled as such rather than folded into H2. Background jobs recompute constantly; without this, a margin call would evaporate within milliseconds of being raised, with nothing economic having changed. Conceptually: `Near Margin` = "in the band, no call has been raised"; `Initial Margin Call` = "a call was raised, and calls hold until cured." | `TransitionMatrixTest` (prev=Initial MC, band B1), `RuleApplied.INITIAL_CALL_HELD_UNTIL_CURED` |
| H6 | Rule 5's bullet says a `link` "must never override" a preexisting `MMC`/`Liquidation` — unconditionally. What if the requirement has *already* dropped back below the Initial limit by the time the `link` fires? | **Still unconditional.** A `link` leaves a called CA untouched even when an ordinary recompute would have cured it. | The literal wording has no carve-out for this case, and the practical impact is nil: the very next ordinary recompute cures it anyway. Reading it as "unconditional" is simpler than inventing an exception the text doesn't state. | `TransitionMatrixTest` (`link`, prev=MMC/Liquidation, band B0), `RuleApplied.LINK_CANNOT_OVERRIDE_CALLED` |
| H7 | The spec only orders `Initial < Maintenance < Liquidation` LTVs; it never explicitly ranks `Near Margin` against `Initial Margin Call`. | `Near Margin` sits **below** `Initial Margin Call` in severity. | Supported by (a) the order the PDF lists the five statuses in, and (b) the domain reading that a warning is less severe than an actionable call. This ordering is what the whole hysteresis/ratchet mechanism (`maxOf(prev, candidate)`) depends on — `Status`'s declaration order encodes it directly. | Implicit in every test that compares severities; declared in a comment on `Status.kt` |
| H8 | `collateralValue × LTV` can, in general, be an inexact quantity — should the derived limits be rounded to the collateral or requirement asset's scale? | **No rounding, anywhere on the path.** Compare at full `BigDecimal` precision. | Turns out to be stronger than a style choice: the full computation is `balance × rate × ltv` — three multiplications, zero divisions. `BigDecimal` multiplication is exact and never needs a `MathContext`; the only division-shaped operation is `percent → ratio`, done via `movePointLeft(2)`, an exact scale shift that cannot round or throw. There is no rounding decision left to make on the main path — any rounding would only ever be introduced by future code, and would shift a threshold in a spec whose axis is "at or above." | `MoneyTest` (`Ltv.ofPercent` yields an exact ratio); no rounding call exists anywhere in `main/` to test against |
| H9 | Several degenerate inputs aren't addressed by the base rules: `requirement = 0` with `collateral = 0` (every limit is 0, and `0 >= 0` would literally fall through to `Liquidation`); LTVs out of order; mismatched assets; negative amounts; a missing price. | `requirement = 0` is special-cased to `Good Standing` regardless of collateral (there's nothing to cover). Everything else — unordered LTVs, negative amounts, asset mismatches, a `PriceSource` that has no quote — is rejected with an exception at construction/valuation time, never silently misclassified. | Zero collateral backing a real requirement genuinely is `Liquidation` and is left as such. The zero/zero case is the one degenerate input that "makes the system's own math say something is on fire" for a CA that has literally nothing to lose — I treat it as the requirement being trivially satisfied. The guard lives in `Limits.locate` alone, so it never leaks into the rest of the pipeline. | `EdgeCaseTest` |
| H10 | A brand-new CA being linked for the first time has no previous status. | Modelled as `currentStatus: Status? = null`. With no history, no history rule constrains the outcome — only the base/link classification applies. | The domain fact "there is no history yet" deserves a `null`, not a synthetic default status that would silently participate in the history rules. | `TransitionMatrixTest` (prev = none, all bands, both events) |

The naming split is deliberate too: the **input** field is `currentStatus` (the status in
effect *before* this assessment runs); the **output** field on `HealthAssessment` is
`previousStatus` (by the time you're looking at a result, it is the previous one). Calling
the input `status` would read as if it were the answer.

## A risk I'd raise before shipping

H3, worked with real numbers: link a loan to a CA that's already massively
under-collateralized — say a requirement of 100,000 USDC against a Liquidation limit of
48,000 USDC. Rule 5 puts it in `Initial Margin Call` on that link ("regardless of which
higher limit is crossed"). Rule 6 then holds it there — literally, indefinitely — even as
the price keeps falling, until the requirement drops all the way back under the Initial
limit (30,000 USDC in this example). A position that is deeply liquidatable never reaches
`Liquidation`.

I implemented rule 6 as written, because I believe the evidence points to it being
intentional (see H3 above). But if this were headed to production, I would flag it to
risk/product before shipping rather than let it ship silently: this is a real business risk
hiding behind literal correctness, not a hypothetical.

## Design decisions & tradeoffs

- **A pure core.** `assess` does no I/O, reads no clock, and holds no mutable state. Prices
  come in through a single `fun interface PriceSource` port, satisfied with a one-line lambda
  in every test — no mocking framework needed. This also makes the "total for valid inputs"
  claim below meaningful: nothing in the pipeline can throw for reasons unrelated to the
  inputs.
- **Typed, exact money.** `Asset`, `Money`, `Rate` and `Ltv` are dedicated types over
  `BigDecimal`, never `Double`/`Float`. `Money` is deliberately **not** a `data class`: the
  generated `equals` would delegate to `BigDecimal.equals`, which is scale-sensitive
  (`39000` != `39000.00`), and this spec's every rule pivots on exact "at or above"
  comparisons at a threshold. `equals` is hand-written and anchored to `compareTo` instead;
  `MoneyTest` pins the distinction directly.
- **An enriched result, not a bare enum.** Covered under "The public API" above —
  `HealthAssessment` carries the limits, the band, the pre-history candidate, and the rules
  that fired, because the brief explicitly asks for reasoning that's legible to controllers
  and jobs, not just a terminal value.
- **A short ordered list of named rules, not a rule engine.** The history-rule step
  (`applyHistory` in `CollateralHealth.kt`) is five `when` branches, each citing its PDF rule
  number in a comment, evaluated in order with first-match-wins. This is deliberately *not* a
  generic rule engine, a DSL, or a `Strategy` registry: there are eight rules total, and a
  rule engine for eight rules is exactly the over-engineering this exercise is designed to
  surface. Changing rule 6 tomorrow means changing one line, not a config schema.
- **Interpretations are hardcoded, not configurable.** I considered exposing the ambiguous
  calls (H2's hysteresis, H3's non-escalation, ...) as flags on a `HealthPolicy`-style object,
  and rejected it. There isn't a principled line between "this ambiguity gets a flag" and
  "this one gets hardcoded" — there are roughly ten of them, in a 3-hour exercise that is
  explicitly testing judgment about what to build. Parameterizing two of ten just moves the
  same discussion into a config schema and invites the question "why is rule 6 a flag but the
  hysteresis isn't?", which has no good answer. The two real options are configuring *all* of
  them (which is the over-engineering trap) or fixing and documenting all of them — I did the
  latter.
- **Exceptions at construction, not a `Result`/`Either` return type.** `require` in the value
  types' constructors makes illegal states — unordered LTVs, negative amounts, mismatched
  assets — impossible to construct in the first place. `assess` is then total for any input
  that was legal to construct, which is a stronger and simpler guarantee than threading an
  error type through the pipeline for cases that can no longer occur by the time `assess`
  runs.

## What I left out, and what I'd do next

Left out on purpose, because the brief explicitly excludes them or because building them
would be answering a question nobody asked:

- Persistence, HTTP, a CLI, a DI framework, Docker, CI — the brief excludes all of these by
  name.
- A generic rule engine, a rules DSL, or a `Strategy`-with-plugin-registry pattern for the
  eight rules — see "Design decisions" above.
- Event sourcing, or any state-machine framework — the 48-cell transition matrix *is* the
  state machine, expressed as a table and a test, not as infrastructure.
- A real price feed, caching, staleness detection, or retries — a `fun interface` and a
  lambda are enough for a pure valuation step; those concerns belong to whatever adapter
  implements `PriceSource` in the real system, not to this library.
- Multi-asset collateral baskets — the brief specifies a single collateral asset, and I kept
  to that. It's the obvious next extension, and `Money`/the valuation step is the seam where
  it would go: `valuate` would become a fold over a basket, and everything downstream is
  already asset-agnostic (limits and bands are computed on `Money` in a single asset).
- Benchmarks, concurrency, metrics, i18n — no signal in a pure, single-threaded value
  computation.

What I'd genuinely do with more time, in priority order:
1. Emit a first-class domain event on `assessment.changed` (a `StatusChanged` value with
   before/after and `rulesApplied`), rather than leaving it to each caller to notice the
   diff and log it themselves — the background-job snippet above does this manually today.
2. An audit/trace ID threaded through `HealthAssessment` so a specific assessment can be
   correlated back to the price tick or API call that triggered it.
3. Revisit whether an upper bound on LTV (>100%) should be rejected — the spec never says,
   and I didn't invent a limit it doesn't state, but a real system would probably want one.

## Questions I'd ask + time spent

- Is the `Initial Margin Call` ceiling (H3) actually intentional, or should a sufficiently
  severe drop be allowed to escalate straight to `Liquidation` even from `Initial Margin
  Call`?
- Should a `link` be allowed to cure an already-resolved `MMC`/`Liquidation` (H6), given that
  the next ordinary recompute does it anyway — or is "never" the deliberately simpler rule?
- Is `Near Margin` client-visible (a notification-worthy status) or purely internal
  bookkeeping? That changes how much weight H1's Near-Margin/Initial-Margin-Call split
  carries downstream.
- Do the derived limits ever need to be rounded to the collateral or requirement asset's
  display scale for a UI, even though the domain computation itself has no rounding step
  (H8)?

Time spent: within the 3-hour budget described in the brief, split roughly as reading and
writing down assumptions first, then the domain types, then the pipeline and history rules,
then the transition matrix and remaining tests, then this README last. Nothing here was cut
for time — the checklist below and the test suite are both complete.
