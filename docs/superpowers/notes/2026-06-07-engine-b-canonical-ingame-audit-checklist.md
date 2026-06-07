# Engine-B Canonical Pipeline — In-Game Audit Checklist (2026-06-07)

Headless gates (Stages 1–3) are GREEN on the real material LUT; `liborge.so` rebuilt;
Java `:core:test` + `:core:integrationTest` (24/0, skipped=0) green on the real lib; both loaders build.
The live JNI fluid step now runs the canonical **ENCRYPT → RESOLVE → DECRYPT** resolver
(off the retired `force_advect.hpp`). This is the user's gate — headless-green is NOT done.

## SHIPPED — should PASS in-game

1. **Lava sinks under water (audit #2).** Drop lava onto a water pool → lava **descends below** the
   water (does not pin on top). Force-difference swap; per-species mass stays constant (1 bucket lava
   stays ~1 bucket, 1 bucket water stays ~1 bucket).

2. **No residue / no ghosts (audit #3).** After fluid drains/falls:
   - **No** sub-visible water film lingering where water left.
   - **No** `inject=orge:air` log flood.
   - Drained/broken cells read as air/void cleanly (no 0-mass labelled-fluid ghosts).
   - Mechanism: a draining cell keeps ≥ its `min_mass` (cohered) or empties fully to VACUUM — it is
     never left holding a sub-min crumb. Falling water still fully relocates (no min_mass trail).

3. **No fabrication / no overshoot (audit #4 — don't regress).** 1 bucket stays ~1 bucket; no cell
   exceeds `max_mass`; lava→stone / water amounts stay sane; no temperature ghosts.

## BANKED — expected to NOT work yet (do NOT file as a regression)

4. **Leveling / horizontal flow (audit #1) — DEFERRED by you 2026-06-07.** Water will NOT self-level a
   tall column down to a short one, and will NOT flow horizontally across an air gap. Root: the resolver's
   `^γ` EOS ramp is zero-width under `max_mass == default_mass` (overshoot pins instead of relaxing), and
   leveling-through-air needs a horizontal cross-species displacement the canonical spec doesn't describe.
   The connected-body leveling mechanism + a relaxing pseudo-compressible EOS are the open design work
   (banked test: `ORGE-ENGINE/tests/engine_b_stage2_leveling_test.cpp`).

## ⚠ KNOWN CONCERN — PERFORMANCE (watch for in-game lag)

The canonical resolver is **~100× heavier per cell** than the retired 3-local-pass force-advect
(measured ~0.5–1.0 Mcell/s). A whole-world step over loaded columns may **lag** in-game. This is a
calibration/optimization item (the plan's deferred Stage-4 §G.2), not addressed here. If the world
stutters badly, that's expected from this change — note severity (playable / borderline / unplayable).

## How to report back
For each SHIPPED item: PASS / FAIL (+ what you saw). For item 4: just confirm it's still deferred
(or tell me if you now want it tackled). For performance: playable / borderline / unplayable.
On any SHIPPED failure I reproduce headless-first, fix against the canonical spec, conserve
grand + per-species, adversarially review, then push (engine + gitlink).
