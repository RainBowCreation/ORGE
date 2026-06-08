# T1 — RED acceptance tests (real LUT) · read 00-MASTER-RULES.md first

**Goal:** write the failing tests that define "leveling/relief/displacement works," BEFORE any engine
change. One new test file. All must be RED now (they exercise behaviour T2–T5 will add) and assert
**movement AND conservation+bounds**.

**Authoritative:** plan T1; decomp **§8** (every sub-case is specified there); canonical "in-game audit
findings". Math/flux shapes: unified-formula §C/§J.5.

## STRICT DO
- Use the **real LUT** fixture `tests/engine_b_real_lut.hpp` + its `grand_mass`/`species_mass`/
  `grand_energy` helpers. Build scenes in ONE chunk (or minimal multi-chunk where the case needs it).
- Assert **it MOVED / leveled** (surfaces converge, lava sinks, drop blocked, lowest-`p` consumed) AND
  grand+per-species mass exact every step AND no `max_mass` overshoot.
- Cases (each its own test fn): hydrostatic-REST (uniform column does NOT move), leveling (unequal columns
  converge monotone, no overshoot past mean±min_mass; a flat pool never climbs), U-tube (decomp §8.4),
  solid-shelf shielding (fluid under a stone shelf shielded from the stack above; pools either side of a
  vertical wall don't level through it), swap-by-resistance (lava-on-water inverts; light-on-heavy never;
  metastable when pair resistance high), min_mass gate (130→875 blocked; 250→`[1000,125]`, decomp §8.7),
  lowest-`p` consumption (consumes the lowest-`p` *reachable* cell, not a buried higher-`p` bubble; not
  gated by max_mass).
- Register every test in **CHEAP_TESTS** in `tests/run_tests.sh`. Confirm they compile and FAIL for the
  *right* reason (no flow / no swap / fragment created), not a harness error.

## STRICT DON'T
- ❌ Do NOT write any production-code change in this task — tests only.
- ❌ Do NOT use synthetic Materials or hand-tuned `chi` — real LUT only (master rule).
- ❌ Do NOT assert merely "bounded"/"didn't crash" — that masked the dead engine last time. Assert the
  physical outcome.
- ❌ Do NOT model depth-pressure in the test via "Σ mass above" expectations — assert the *observable*
  (heights equalise, masses conserve), not an internal pressure formula.

## Stale existing tests (triage per MASTER-RULES "🧨 STALE TESTS")
- **Reuse, don't duplicate:** the banked `engine_b_stage2_leveling_test` already encodes the leveling
  scenario as RED-by-design. **Re-author it to the spec** (decomp §8) and treat flipping it GREEN as the
  T1→T2 target — do NOT write a parallel test and leave the banked one rotting.
- If you find existing tests asserting dead behaviour (cross-species no-flux, chi gate, `swap_threshold`
  value, rest-pressure-stays-0), **flag them in your report** for re-author/retire in their owning task
  (T2/T4) — do not silently delete them here.

**Acceptance:** new/re-authored tests compile, all cases RED for the correct physical reason, cheap tier
otherwise green, conservation helpers wired, stale tests flagged. Report each test name + the RED reason.
