# Engine-B RESOLVE-force rebuild — handoff index

> # ⚠ HISTORICAL 2026-06-10 (except 00-MASTER-RULES): the T1–T8 track shipped. The current authority
> chain is **DESIGN-LAW → DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-10 → specs/2026-06-10-engine-b-unified-
> spec-v4.md → notes/2026-06-10-physics-math-audit-report.md** — start at the rewritten
> [`00-MASTER-RULES.md`](00-MASTER-RULES.md). The T-file list and the "Authoritative design" list below
> describe the SHIPPED track, not the current model (decomp §8 is superseded by v4 §3–§7).

Fresh-agent handoffs for the emergent-pressure RESOLVE rebuild (subagent-driven, on `rebuild`).
**Every task file says "read 00-MASTER-RULES.md first" — enforce that.**

## Order
- **00-MASTER-RULES.md** — shared law: spec>code, authoritative-docs list, **STALE / DO-NOT-TRUST** files,
  **🧨 STALE TESTS triage** (a red existing test may be correct-failing, not a bug), conservation,
  TDD/subagent process, report format, suggested skills.
- **T1-red-tests.md** — RED real-LUT acceptance tests (rest/leveling/U-tube/shelf/swap/min_mass/lowest-P).
- **T2-emergent-pressure.md** — THE CRUX: pressure emerges via reflection in one snapshot pass (HIGH RISK).
  ⚠ **DONE but in-game audit found it INCOMPLETE — see T2.5.**
- **T2.5-horizontal-flow.md** — 🔴 **NEW (in-game finding 2026-06-08): horizontal flow is DEAD.** T2's
  leveling "GREEN" was a false positive — the test only covered a vertical U-shape. Flat pools don't level,
  columns don't slump sideways. T2.5 adds the two RED scenes (same-height 1000\|500; dam-break) and fixes the
  §8.4 lateral path. **Also re-validates T3's `YG_B`, which currently passes vacuously** (wall "blocks"
  indistinguishable from dead lateral flow). **Serialize AFTER T4** (same file).
- **T3-yield-gated-transmission.md** — fluid through / locked solid blocks / vacuum resets.
  ⚠ `YG_B` (lateral wall) passes vacuously today — re-validated in T2.5.
- **T4-swap-pair-resistance.md** — swap barrier = pair resistance, drop `swap_threshold`.
- **T4-NOTE-horizontal-flow-is-not-your-bug.md** — paste to the in-flight T4 agent: horizontal-flow gap is
  T2.5's, not T4's; stay in the vertical-swap lane.
- **T5-minmass-gate.md** — the 130/875/250 cohesion rule.
- **T6-stability-soak.md** — overshoot/oscillation soak + `head_relax`.
- **T7-checkpoint-push.md** — full green, rebuild `.so`, push both repos, in-game gate (controller-run).
- **T8-spec-pure-placement.md** — inject+deposit, rip out chain (ONLY after T7 proven in-game).

## Authoritative design (in the repo, read these — not the C++)
- `docs/superpowers/specs/2026-06-07-engine-b-CANONICAL-pipeline.md`
- `docs/superpowers/specs/2026-06-07-engine-b-vector-map-decomposition-design.md` (**§8** = refined model)
- `docs/superpowers/plans/2026-06-07-engine-b-overburden-head-resolve.md`
- design committed at `rebuild` `0cecfe9`.

## Suggested skills
`superpowers:subagent-driven-development` (controller loop), `superpowers:test-driven-development`,
`superpowers:requesting-code-review`, `superpowers:finishing-a-development-branch`.
