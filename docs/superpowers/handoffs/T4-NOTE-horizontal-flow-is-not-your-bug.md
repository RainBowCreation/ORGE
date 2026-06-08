# 📌 T4 heads-up — if something looks broken with horizontal flow, it's NOT your bug

You (T4) own **only** the cross-species **vertical buoyant swap** = pair resistance (lava sinks under water).
While you were working, the in-game re-audit found a **separate, pre-existing gap**: **same-species
HORIZONTAL flow is dead** — a flat pool does not level, a column does not slump sideways. This is owned by a
new task **T2.5**, not you.

So, if you hit any of these, **do NOT chase them, do NOT widen your scope, do NOT "fix" them**:
- a swap test where, after the swap, the displaced liquid just **sits** instead of spreading/settling sideways
- a pool/column that **won't level horizontally** around your swap scene
- water beside air that **won't flow into the air** horizontally

Those are the known T2.5 horizontal-flow gap. Your swap is **vertical** (buoyant `(ρ_up−ρ_low)·g·V`,
overburden cancels) — verify it as a **vertical reorder + a permutation** (per-species mass exact), with the
**pair-resistance** barrier. Do not add horizontal flux, free-surface heads, or pressure terms to make a
scene "settle" — that's T2.5's file and a possible spec decision for the user.

If a swap test genuinely needs horizontal settling to express its assertion, **simplify the assertion to the
vertical reorder + conservation only**, and note in your report: "horizontal settle deferred to T2.5." Then
finish your three reviews (spec, quality, **adversarial conservation**) as planned.

Stay in your lane: **vertical swap, pair resistance, conservation.** Everything horizontal = T2.5.
