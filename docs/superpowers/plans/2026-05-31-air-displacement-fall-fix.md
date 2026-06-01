# Fix: falling water leaves a 1.2 kg air residue trail + spawns 0 kg/0 K ghost ice

Two confirmed root causes from an in-game tick-stepped repro (pour 1 water on a floating block):

- **Bug A — 1.2 kg residue trail (grows +1.2/cycle, cascades).** Fall *absorbs* the air cell below
  instead of *displacing* it: `cap = maxMass − massOut[below]` (`orge_kernel.hpp:155`) and the air
  below holds `orge:air`'s `default_mass` = 1.2 kg, so the donor keeps `1000−998.8 = 1.2 kg`
  (relabeled water). The kernel comment (`:303-304`) confirms liquid/air is intentionally routed
  through fall-as-absorb; the `(2c)` swap that would displace air is gas-only (`swapGated` `:307-312`).
- **Bug B — 0 kg/0 K ghost ice (re-spawns each thermal second).** Drained cells get `Tout=0 K`
  (`orge_kernel.hpp:160`); `PhaseRule.targetBlock` freezes purely on temperature with NO mass guard
  (`PhaseRule.java:29`), and on a coincident tick §7 runs before the reconciler clears the empty
  water block (`Scheduler.java:342` then `:344`), so the empty cell freezes to ice.

Fix: make liquid-fall-into-real-air a **displacement swap** (water sinks to fill, the 1.2 kg of air
rises into the vacated cell — no residue, exactly conservative), and guard §7 against empty cells.

## Constraints (non-negotiable)

- Two repos: ENGINE = `/home/claude/ORGE/ORGE-ENGINE` (own git, branch `main`), MAIN = `/home/claude/ORGE`
  on `rebuild`. `cd` into the right repo before `git`; NEVER `git add` across the boundary.
- `JAVA_HOME=/home/claude/jdk21` on every gradle call.
- Any advection-rule change in `orge_kernel.hpp` is mirrored bit-identically in `sim_engine.hpp` in
  the SAME commit; the ENGINE parity test (`advection_parity_test`) stays green.
- No JNI/ABI change (the swap is internal kernel logic; inputs/outputs unchanged).
- ENGINE: commit per task on a work branch `feat/air-displacement`; merge/push to ENGINE main +
  rebuild `liborge.so` + bump MAIN gitlink + bundle the `.so` ONLY at the integration task.
- MAIN: push `origin/rebuild` after every MAIN commit.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Task 1 [MAIN] — Bug B: §7 skips empty cells (Java-only, independent, quick)

`PhasePlanner.plan` must not phase-change a cell with ~no mass (an empty/drained cell is not a fluid
that can freeze or boil). Add a per-cell mass input and skip cells whose mass ≤ a small floor.

- `PhasePlanner.plan(...)`: add a `float[] mass` (or `IntToFloat massAt`) parameter; in the loop,
  `if (mass[i] <= PHASE_MIN_MASS) continue;` BEFORE calling `PhaseRule.targetBlock`. Choose
  `PHASE_MIN_MASS` so a truly drained cell (0 kg) and the air-residue class are skipped but a real
  shallow puddle still freezes — use a small constant (e.g. a few kg; document the choice). A drained
  cell carries 0 kg + 0 K; this guard makes the 0 K unreachable by the freeze rule.
- `MinecraftPhaseChanger`: it already reads `SectionData` (`data.temperatureAt(i)`); also read
  `data.massAt(i)` into the array passed to `PhasePlanner.plan`.
- TDD: `PhasePlannerTest` — a 0 kg, 0 K water cell yields NO transition (was: ice); a 1000 kg, 250 K
  water cell still freezes to ice; a 1000 kg, 400 K water cell still boils to steam.
- Build + `:core:test` green. Commit MAIN, push `origin/rebuild`.

## Task 2 [ENGINE] — Bug A: fall-into-real-air becomes a displacement swap (kernel + sim_engine)

On a new ENGINE work branch `feat/air-displacement`.

In `orge_kernel.hpp` `(2a)` FALL (`~:144-161`), split the destination cases. Keep the gas-donor skip.
For the `below` cell:
- **same-species fluid with capacity** → unchanged deposit (leveling down).
- **empty cell** (drained, `massOut[below] <= ADV_EPS_MASS`, or the void sentinel `matIx[below]==0`)
  → unchanged deposit (no air mass to displace; `cap = maxMass − 0`, no residue).
- **real air** (`matIx[below]!=0 && lut.air[matIx[below]] && massOut[below] > ADV_EPS_MASS`) → **SWAP**
  the two cells (only when the donor fits, `m <= lut.maxMass[matIx[i]]`; otherwise fall back to the
  deposit branch — rare over-full case):
  ```
  float mB = massOut[below]; uint16_t matB = matIx[below]; float tB = Tout[below];
  massOut[below] = m;        matOut[below] = matIx[i]; Tout[below] = Tout[i];   // water sinks, fills
  massOut[i]     = mB;       matOut[i]     = matB;      Tout[i]     = tB;        // air rises into donor
  ```
  No `<= eps → 0` collapse on the donor (it now holds the air's ~1.2 kg legitimately). Cascades
  correctly top-down (water sinks via successive swaps, air bubbles up).
- Mirror ALL of the above bit-identically in `sim_engine.hpp`'s `advect_chunk` fall block (it writes
  `mass_kg`/`T_curr`/`matIx` in place; use the same `mat0` snapshot semantics already there).
- ENGINE tests: a new `test_air_displacement` (or extend `test_air_sink`): a water cell over a real
  `orge:air` cell (non-zero LUT idx, `air()==true`, 1.2 kg) → after one step the below cell is full
  water (1000), the donor is air (1.2 kg, air species), total mass conserved, **no 1.2 kg water
  residue**. Add a kernel-vs-`sim_engine` parity assertion for the SAME input. Update any existing
  fall/wetting case that asserted the old absorb/residue behavior to the swap behavior (keep mass
  conserved). `tests/run_tests.sh` green (incl. `advection_parity_test`). Commit on `feat/air-displacement`.

## Task 3 [MAIN] — §9 symmetric air credit so the swap conserves per-species (Java-only, ABI-independent)

The swap makes a donor cell go **fluid-in → air-out** while its partner goes **air-in → fluid-out**.
`StepValidator.massConservedPerSpecies` already credits the air-in/fluid-out cell's `before` mass to
the output fluid's `sumBefore` (the wetting/absorb credit). For a swap that over-counts by the air
mass unless the matching fluid-in/air-out cell is credited symmetrically. Add:
- alongside the existing `else if (in != 0 && lut.get(in).air() && out != 0 && lut.get(out).fluid())
  sumBefore[out] += before[i];`, add a symmetric branch: when a cell is `in.fluid() && out.air()`,
  credit its OUTPUT air mass to the input fluid species' `sumAfter`:
  `if (in != 0 && lut.get(in).fluid() && out != 0 && lut.get(out).air()) sumAfter[in] += after[i];`
  (Confirm exact `sumAfter` construction in the method; the net effect must be: for a swap,
  `sumBefore[water] == sumAfter[water]` exactly; for wetting, behavior unchanged; for a genuine
  fabrication, still REJECTED.)
- TDD in `StepValidatorMassTest` (or the per-species test): (a) a swap section (one cell water-in/air-out,
  the cell below air-in/water-out, masses swapped) → `massConservedPerSpecies` TRUE; (b) the existing
  wetting/absorb case still TRUE; (c) a genuine water fabrication (no matching air-out) still FALSE.
- `:core:test` green (on the OLD `.so`; pure Java). Commit MAIN, push `origin/rebuild`.

## Task 4 [MAIN+ENGINE integration] — rebuild `.so`, bundle, gitlink, native-backed regression

- ENGINE: merge `feat/air-displacement` → `main`, push. Rebuild
  `JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`; copy to
  MAIN `core/src/main/resources/natives/linux-x64/liborge.so`. Bump gitlink (`git add ORGE-ENGINE`).
- MAIN: update/add a native-backed `AuditScenarioTest` case — water poured over a real-air column:
  after K advection steps it has fully sunk to the floor as full (1000 kg) cells with the air
  displaced upward (air cells above), **no 1.2 kg residue anywhere**, total mass conserved, and §9
  (`massConservedPerSpecies`) returns true every step. Re-enable/retune any fall test the swap changed.
- `:core:test` (native-backed) + `:fabric-1.21:build :neoforge-1.21:build` green; confirm bundled
  `.so` md5 matches the ENGINE build. ONE MAIN commit (`.so` + gitlink + regression together), push
  `origin/rebuild`.

## Verification
- ENGINE: parity + advection suites green incl. the new air-displacement case.
- MAIN: `:core:test` green incl. the §9 swap test + native-backed no-residue regression; both loaders build.
- In-game (user): pour water on a floating block → it falls to the floor leaving NO water/ice residue
  in the cells it passed through (they return to air); the ghost ice is gone; combined mass = 1000.
