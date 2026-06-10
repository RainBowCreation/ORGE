# Issue #11 — bug-#3 enthalpy-carrier gate: IN-GAME AUDIT CHECKLIST (the human gate)

Headless green is NOT done. The law-gated acceptance suite (`engine_b_bug3_accept_test`,
re-authored `engine_b_conduction_stability_test`, both on the real LUT) proves the enthalpy
carrier removes audit bug #3 **by construction** in headless runs; a human must confirm the
same in a live world before #11's behavior is considered trusted.

## Setup
- Build/install the current `rebuild` parent (engine gitlink includes `engine_b_bug3_accept_test`;
  `liborge.so` rebuilt from the same engine commit). Either loader; NeoForge matches the
  original 2026-06-06 audit.
- Use `/orge section` / `/orge get` to read per-cell `T`/mass while auditing.

## Checks (each maps to a headless assert — verify it holds LIVE)

1. **Bug-#3 original repro — bounded.** Place ONE water bucket (290 K) inside a sealed-ish
   air pocket (glass box ok). Wait several engine dispatches.
   - PASS: section `T min/avg/max` stays ~290 K everywhere (±a few K). No 0 K, no 6000 K,
     no spread of fabricated heat/cold.
   - FAIL: any cell pinned at 0/6000 K or section avg drifting with no source.
2. **Bounded NEXT TO a hot source.** Repeat with lava 1–2 blocks away (behind stone or under
   the pocket).
   - PASS: temps rise only smoothly/locally (real conduction is slow); no cell exceeds the
     lava temperature; no oscillation between extremes on thin/wisp cells.
3. **Heat actually MOVES (right reason).** Water column or wisp adjacent to lava: nearby air/
   thin cells warm over time (use `/orge get` repeatedly).
   - PASS: monotone-ish warming toward (and never past) the source temperature.
   - FAIL: temps frozen at 290 forever (a dead engine is "bounded" too) — that's a regression.
4. **Mass still moves + conserves.** The bucket falls/spreads through air; `/orge section`
   grand mass constant over dispatches (no over-accumulation, no vanishing water).
5. **Thinned cells.** After flow leaves wisp cells (~1e-6 kg), inspect a few: their `T` must
   read a plausible neighborhood value, never 0/6000 K.

## On any FAIL
File it against the umbrella #5 with `/orge` readouts (coords, T min/avg/max per dispatch,
mass). Do NOT relax the headless asserts to match; the headless suite is the floor, the
in-game audit is the gate.
