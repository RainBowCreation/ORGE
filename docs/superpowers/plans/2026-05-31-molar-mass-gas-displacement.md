# Plan — §11 Phase A: vacuum + finite compressible gas (molar-mass displacement)

Implements the bug fix from `docs/superpowers/specs/2026-05-31-molar-mass-gas-displacement-design.md`:
water poured into N cells must end at **exactly 1000.0 kg**, not `1000+(N−1)·1.2`. Root cause — horizontal
wetting *relabels* the destination air cell's 1.2 kg as water. Fix = treat air as a real, finite,
compressible, **conserved** gas that liquid **displaces** (never consumes); breaking a block makes
**vacuum** (0 mass), not air.

**Scope = Phase A only.** Molar mass is *plumbed* to the engine (data) but does not yet *drive* gas motion
(that is banked Phase B). Steam stays fixed-mass. Air `min 0.001 / default 1.2 / max 1000`.

## Constraints (non-negotiable)
- Two repos: ENGINE `/home/claude/ORGE/ORGE-ENGINE` (own git, `main`); MAIN `/home/claude/ORGE` (`rebuild`).
  NEVER `git add` across the boundary (only the gitlink `git add ORGE-ENGINE`).
- `JAVA_HOME=/home/claude/jdk21` on every gradle call. Push `origin/rebuild` after every MAIN commit.
- ENGINE: commit per task on work branch `feat/molar-gas`; merge/push + rebuild `liborge.so` + bundle +
  bump MAIN gitlink ONLY at the integration task (INT).
- `orge_kernel.hpp` MUST stay **bit-identical** to `sim_engine.hpp` (`advection_parity_test` green) for
  every advection change, IN THE SAME COMMIT.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Subagent-driven TDD: fresh implementer per task + two-stage review (spec compliance, then code quality).
- Headless tests missed earlier air bugs ([[orge-air-sink-fix]]); every task that can MUST assert
  **per-species conservation incl. air** and use the **real LUT flags**, not synthetic stubs.

---

## Task M1 [MAIN] — datapack + crash-guard + molar reachability (PURE DATA/PLUMBING, zero behaviour change)
Pure-Java + JSON, **no flag flip, no §9 change, no kernel/native change** — `:core:test` stays green
trivially. (The `air→fluid/gas` ACTIVATION is deferred to the kernel/§9/INT tasks where behaviour actually
changes — see the "Flag-flip sequencing" note below. Flipping `Material.fluid()/gas()` here would break the
5 old-semantics tests that M3/E1/INT rework, which is why M1 must NOT touch the flags.)
- `air.json`: add `"min_flow_mass": 0.001`, `"max_mass": 1000` (keep `default_mass 1.2`, `molar_mass 0.029`,
  `state "air"`).
- `MaterialCodec`: extend the gas crash-guard so `state=air` also requires `min_flow_mass > 0`
  (mirror the `state=gas` guard); keep the existing `state=gas` guard.
- Confirm `molarMass` is already a `Material` field reachable by the engine bridge (it is — M2 adds the
  actual `molar[]` array). No code change needed for reachability.
- Do **NOT** change `Material.fluid()`/`gas()`, `BatchMarshaller`, `StepValidator`, or any native test.
- TDD: a unit test asserting the reloaded `air` material has `minFlow=0.001`, `maxMass=1000`,
  `molarMass=0.029`, `state=air`; and the crash-guard rejects a `state=air` material with no
  `min_flow_mass`. (Do NOT assert `fluid`/`gas` flags — those flip later.) `:core:test` green. Commit MAIN, push.

### Flag-flip sequencing (resolves the M1/M3/E1 coupling)
Activating air as an engine fluid/gas (`lut.fluid=1`/`gas=1` for air) changes both §9 routing and kernel
behaviour, and the old native E2E (`AuditScenarioTest`) asserts the OLD air-discard against the bundled
`.so`. So the activation is staged, NOT done in M1:
- **E1–E3 (ENGINE)** develop the new kernel against the C++ parity harness, which builds its own
  air-as-fluid/gas LUT — independent of the Java flags.
- **M3 (MAIN)** reworks §9 to conserve air as a tracked species keyed on `.air()`/`.gas()` (NOT requiring
  `Material.fluid()` to flip); updates the `StepValidator` tests to the new contract; and `@Disable`s the
  OLD native air-discard assertions in `AuditScenarioTest` with a `TODO(INT)` (they test
  soon-to-be-removed behaviour against the not-yet-rebuilt `.so`).
- **INT** bundles the new `.so` AND flips air to a participating fluid/gas for the live path (decide at INT
  whether that is `Material.fluid()/gas()` or a `BatchMarshaller` mapping from `.air()` — keep Java's
  "placeable fluid block" notion separate from the engine's "advects" notion if they should differ), then
  replaces/re-enables the native E2E with new-contract assertions.

## Task M2 [MAIN] — JNI/bridge: pass a per-material `molar[]` array to the engine
- Extend the native `step()` bridge + the Java side that marshals per-material arrays (cond/heatCap/visc/
  fullMass/minFlow/maxMass/flags) to also pass **`molar[]`** (kg/mol, indexed by material). Stub/native
  fallback both updated.
- This is plumbing: the kernel will receive but not yet use it (until E-tasks). Keep the existing arrays
  bit-for-bit; just add one.
- TDD: bridge round-trip test — the LUT seen by a (test-double or real) engine step includes the molar
  array with air=0.029; native fallback path still runs. `:core:test` green. Commit MAIN, push.

## Task E1 [ENGINE] — `MatLUT.molar` + vacuum sentinel; remove the `lut.air` discard semantics
Work branch `feat/molar-gas`. **Bit-identical kernel/sim_engine, same commit.**
- Add `const float* molar;` to `MatLUT` (both `orge_kernel.hpp` + `sim_engine.hpp`); wire it through the
  call signature + the parity harness LUT builder. Unused by motion yet (plumbing parity).
- **Replace the `lut.air` "empty/adopt" semantics** with the **vacuum** sentinel: a cell a fluid/gas may
  move INTO is `matIx==0 OR massOut<=ADV_EPS_MASS` (vacuum) **OR** a strictly-lighter fluid (density rule,
  E2). An air-FLAGGED material with real mass is **no longer** an "adopt-and-discard" target — it is a
  real gas to be displaced. Keep gas/fluid flags.
- For THIS task, keep behaviour otherwise identical except: where the old code adopted/discarded a
  real-air cell on fall/wet, it must now treat that air as a lighter **fluid to displace** (route to the
  swap/leveling path, not the absorb path). The headline parity case: water on a floor cell beside/above a
  real-air cell → the air's mass is preserved (moved), not folded into water.
- Parity: extend `advection_parity_test` — (a) a fluid moving into **vacuum** (0-mass) behaves as today;
  (b) a fluid meeting a **real-air** cell preserves air mass (displaced, not consumed); `kernel ==
  sim_engine` bit-identical; seam/interior mass conserved per species incl. air. `tests/run_tests.sh`
  green. Commit on `feat/molar-gas`.

## Task E2 [ENGINE] — liquid DISPLACES gas (the consumption fix), incl. compressible air
**Bit-identical, same commit.**
- **Horizontal wetting (the bug):** when a supported liquid edge cell would spread into a cell holding a
  *lighter* fluid (air/gas), do a **density displacement**, not a relabel-and-add: the liquid occupies the
  cell and the lighter fluid is pushed out (its mass relocated to where the liquid came from / up via the
  vertical swap), conserving **each** species. No `+1.2` on the water total.
- **Compressible gas receiving displaced gas:** an air cell may exceed `default_mass` up to `max_mass`
  (1000); the donor floor uses `min_flow` (0.001). Use the current-mass = density comparator throughout.
- **Vertical fall into real air** already swaps ([[orge-air-displacement-fix]]); confirm it now routes
  through the same unified displacement and that air is conserved (no residue / no consume) — adjust if
  the E1 sentinel change touched it.
- Parity: the headline test — **`1000 kg liquid spread across N cells → total liquid == 1000.0` and total
  air unchanged** for N = 2..6 (matches the user's repro); plus a compress case (liquid squeezes air from
  k cells into k−1, air mass preserved). `kernel == sim_engine`. `tests/run_tests.sh` green. Commit.

## Task E3 [ENGINE] — gas volume-fill into vacuum (finite gas refills a broken-block cell)
**Bit-identical, same commit.**
- A gas (air) equalizes its **mass** across connected non-solid cells in all 6 directions, expanding into
  **vacuum** (0-mass) and lower-mass gas, compressing toward `max_mass` when crowded, never draining below
  `min_flow`. (Phase A: equalize mass, NOT pressure — molar/temperature do not drive it yet.)
- Anti-oscillation: reuse the antisymmetric flux + one-move/cell claim so a vacuum fills smoothly without
  checkerboarding; settle detection still reports `max|Δ|<ε` so the section can sleep.
- Parity: a vacuum cell adjacent to air → air spreads in, total air conserved, field settles (no
  oscillation over N steps); cross-seam vacuum fill works via the existing halo. `kernel == sim_engine`.
  `tests/run_tests.sh` green. Commit.

## Task M3 [MAIN] — §9 conserves air as a real species; co-step the gas column
- `StepValidator`: air becomes a **tracked, conserved species** in the per-species ledger, keyed on the
  material's `.air()`/`.gas()` identity (NOT requiring `Material.fluid()` to be flipped); **delete the
  air-credit and the "air untracked" exemption**. Per-cell bound allows air up to its `max_mass` (1000).
  Vacuum (0/void) contributes 0 to every species sum. §7-transitioned cells stay exempt.
- `SeamCoStep` / `MinecraftThermalWorld`: co-step the section **above** an active fluid/gas surface so
  rising/displaced gas has a loaded receiver across the Y seam (else strict conservation stalls flow).
- **Old-semantics tests:** update the `StepValidator` mass tests to the new "air is a tracked species"
  contract; `@Disable` the OLD native air-discard assertions in `AuditScenarioTest`
  (`waterFallsAndWetsIntoRealAir…`, `waterSinksThroughRealAirColumn…`) with a `TODO(INT)` — they assert
  soon-to-be-removed behaviour against the not-yet-rebuilt `.so`, and INT replaces them.
- TDD: a synthetic batch where liquid displaces air across a seam now PASSES per-species §9 (air
  conserved); a genuine fabrication still REJECTS; the gas column above an active section is included in the
  co-step set. `:core:test` green. Commit MAIN, push.

## Task M4 [MAIN] — broken block → VACUUM (not air)
- In the block-edit / reseed path (both loaders): when a block is removed to air/nothing, set the ORGE
  cell to **vacuum** (`mass 0`, void material), **not** `default_mass` air. The gas rule (E3) refills it
  from neighbours.
- Same class as [[orge-reseed-misfire-fix]]: record the reconcile signature from the **engine output** so
  the vacuum-fill placements aren't mistaken for player edits. Placing a solid block where gas was
  displaces that gas into neighbours (accept one-cycle batch-hold if sealed+saturated — spec §12.4).
- TDD (headless, pure logic where possible): breaking a block yields a 0-mass void cell (not 1.2 air);
  after a step the neighbours' air has refilled it and **total air is conserved** (no air-from-nothing);
  the reseed guard does not fight the fill. `:core:test` green. Commit MAIN, push.

## Task INT [MAIN+ENGINE] — integrate, rebuild .so, native E2E, both loaders
- ENGINE: merge `feat/molar-gas` → `main`, push. Rebuild
  `JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`; copy to MAIN
  `core/src/main/resources/natives/linux-x64/liborge.so`; bump gitlink (`git add ORGE-ENGINE`).
- MAIN: native-backed E2E (skips if no `.so`) driving the real scheduler path (snapshot + co-step → native
  step → per-species §9 incl. air → writeBack → reconcile). Assert, on the **rebuilt lib**:
  (a) **`1000 kg liquid → spread to N cells → total == 1000.0`** (the headline repro, N=2..6);
  (b) liquid displaces air (air mass preserved, relocated — never consumed);
  (c) **break block → vacuum → air refills from neighbours → Σair unchanged** (no air-from-nothing);
  (d) air compresses (k cells → k−1, air conserved, ≤ max_mass);
  (e) total per-species mass conserved every cycle; no residue/ghost.
  Confirm the bundled `.so` md5 matches the ENGINE build.
- `:core:test` (native) + `:fabric-1.21:build :neoforge-1.21:build` green. ONE MAIN commit (`.so` +
  gitlink + E2E), push.

## Verification
- ENGINE parity + advection suites green incl. vacuum-fill / displace-not-consume / molar-plumb cases
  (`kernel == sim_engine`).
- MAIN `:core:test` green incl. air-as-species §9 + co-step + broken-block-vacuum + native E2E; both
  loaders build.
- In-game (user): pour water across cells → **no mass growth** (the repro is gone); break blocks → vacuum
  then air refills, no air-from-nothing; steam still rises through air; sealed pocket resists; no lag,
  dormancy intact.

## Banked (Phase B — not this effort)
Ideal-gas **pressure** `P = m·R·T/M` driving gas flow/compression (hot air rises, convection) using the
now-plumbed molar mass + per-cell temperature; steam compressibility; cap/hysteresis tuning in the SDL
viewer. See spec §8 + §12.
