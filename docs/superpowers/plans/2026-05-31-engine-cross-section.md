# Engine-native cross-section flow (liquid fall/wet/level + gas buoyancy + thermal)

**Mandate (user):** the Java-layer cross-section reconciler is laggy and wrong — move cross-section
handling INTO the native engine; liquid, gas, AND thermal must all be cross-section compatible.

## Design — reuse the proven antisymmetric seam mechanism

The engine already crosses seams for **conduction** (halo flux) and **same-species fluid LEVELING**
(`(2b-ii)` antisymmetric flux: each section computes Φ(A→B)=−Φ(B→A) from its read-only halo and writes
ONLY its own cell → seam nets to zero, mass conserved, no neighbour write). `sim_engine.hpp` is the
whole-chunk reference; `orge_kernel.hpp` is the per-section production mirror; they are **bit-identical**
(parity test). The gap: vertical **FALL**, **wetting-into-air**, and gas **buoyancy SWAP** are
interior-only. We extend the SAME antisymmetric mechanism to cover them across all six seams. The halo
already carries neighbour mass/mat/temp + the LUT carries air/gas flags ⇒ **no new JNI/ABI data**.

Two gate changes make the larger cross-seam moves legal and sound:
- **§9 batch-level conservation** (Java): per-species mass conserved summed over the WHOLE batch, not
  per-section. Internal seam transfers between co-stepped sections cancel; a full-cell (~1000 kg) fall
  across a seam no longer false-rejects (per-section tol is only ε·N≈41 kg). The per-cell BOUND
  (`≤ maxMass`) stays per-section. This is the named deferred "batch-level Σ" fix.
- **Co-stepping** (Java): the batch includes the loaded face-neighbours of every flow-active section, so
  both sides of any fluid seam are stepped together → antisymmetry is exact. The seam decision uses
  PRE-STEP snapshot masses (existing `(2b-ii)` convention), so an empty "skirt" neighbour that just
  received mass at its inner face does NOT forward it across its outer face the same cycle ⇒ no leak at
  the batch boundary; an empty skirt has no fluid to push out.

## Constraints (non-negotiable)
- Two repos: ENGINE `/home/claude/ORGE/ORGE-ENGINE` (own git, branch `main`); MAIN `/home/claude/ORGE`
  (`rebuild`). NEVER `git add` across the boundary (only the gitlink `git add ORGE-ENGINE`).
- `JAVA_HOME=/home/claude/jdk21` on every gradle call. Push `origin/rebuild` after every MAIN commit.
- ENGINE: commit per task on work branch `feat/engine-cross-section`; merge/push + rebuild `liborge.so`
  + bundle + bump MAIN gitlink ONLY at the integration task.
- `orge_kernel.hpp` MUST stay bit-identical to `sim_engine.hpp` (parity `advection_parity_test` green)
  for every advection change, IN THE SAME COMMIT.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Subagent-driven TDD: fresh implementer per task + two-stage review (spec, then quality).

## Task J0 [MAIN] — remove the laggy/wrong Java seam reconciler (do FIRST, clean slate)
Rip out the post-step Java cross-section machinery so it stops lagging/misbehaving and the engine owns
cross-section:
- `Scheduler.writeBackResults`: delete the post-loop `if (advection) { settleCrossSectionSeams … reconcile }` block.
- `ThermalWorld`: remove `settleCrossSectionSeams` + `TouchedSection` (or leave as unused no-op default
  if cheaper — prefer removing). `MinecraftThermalWorld`: remove the override.
- `FluidReconciler.reconcile(dim,key,outMat,lut)` overload: KEEP it (harmless, may be reused) OR remove
  if unused after J0 — implementer's call, keep the BatchEntry overloads working.
- Delete `CrossSectionFluidLogic`, `CrossSectionSeamPass`, and their tests (`CrossSectionFluidLogicTest`,
  `CrossSectionSeamPassTest`, `SchedulerSeamWiringTest`, `CrossSectionCascadeNativeTest`).
- `:core:test` + both loaders green (the suite shrinks). Commit MAIN, push.

## Task J1 [MAIN] — §9 batch-level per-species conservation
Make the conservation gate batch-level while keeping the per-cell bound per-section.
- `StepValidator`: add a batch-level API — accumulate per-species `sumBefore`/`sumAfter` ACROSS all
  entries (each entry contributes its cells under input species → before, output species → after, exactly
  as `massConservedPerSpecies` does per section), and assert each species conserved within `ε · ΣN`
  (N summed over the batch). Keep the existing wetting/swap/air credits (they remain correct per cell and
  sum up). Provide the per-section BOUND check separately (a cell over its own `maxMass`+ε, except the
  documented over-cap boil parcel, fails immediately — that stays per cell/section).
- `Scheduler.writeBackResults`: restructure so the advection branch (1) per entry: clean + per-cell
  bound-check + writeBack the section, accumulating its before/after species sums into batch arrays;
  (2) AFTER the loop: one batch-level conservation assert. On batch-level FAIL, the safe fallback is to
  hold the whole batch's mass (don't writeBack mass) — but since writeBack already happened per entry,
  EITHER defer the mass writeBack until after the batch check (preferred: validate-then-write) OR keep a
  per-section pre-check and only the cross-seam delta at batch level. Choose validate-then-write:
  accumulate sums first, assert, then writeBack. Preserve the existing "hold previous mass on reject"
  semantics at the batch granularity, and the coincident-tick phase/reconcile ordering.
- TDD: batch of 2 stacked sections where mass moves from one to the other across the seam (per-section
  would reject, batch-level ACCEPTS); a genuine fabrication (mass appears with no donor) still REJECTS at
  batch level; a single-section batch behaves exactly as before. `:core:test` green. Commit MAIN, push.

## Task J2 [MAIN] — co-step face-neighbours of flow-active sections
Guarantee both sides of every fluid seam step together.
- In `MinecraftThermalWorld.snapshot` (or `ActiveSet.activeWithin`): after computing the flow-active
  in-range set, expand it to also include each flow-active section's 6 loaded face-neighbours for THIS
  cycle's batch (so they are stepped + written back together). A neighbour that is empty/settled costs
  almost nothing in the kernel (no fall/spread fires). Do NOT reset their dormancy countdown beyond this
  cycle (transient co-step, not a permanent wake) unless they actually moved — let the existing
  `noteSettle` drive their dormancy.
- Ensure halos are still assembled correctly for the expanded set and that an expanded neighbour with no
  fluid contributes nothing to the batch conservation sum.
- TDD (headless): a flow-active section adjacent to a loaded dormant neighbour ⇒ the snapshot batch
  includes the neighbour; an unloaded neighbour is NOT added (world boundary). `:core:test` green.
  Commit MAIN, push.

## Task E1 [ENGINE] — vertical seam FALL + wetting (kernel + sim_engine, bit-identical)
Work branch `feat/engine-cross-section`.
- In BOTH `orge_kernel.hpp` `(2b-ii)` vertical seam pass and `sim_engine.hpp`'s seam-spread block, in
  ADDITION to same-species leveling, add the **gravity DOWN transfer** across the Y seam: when the UPPER
  side's seam cell (its y=0) is a non-gas fluid and the LOWER side's seam cell (its y=15, via halo) is
  REAL AIR or the SAME fluid with capacity, move `dm` DOWN computed from PRE-STEP snapshot masses:
  `dm = min(upper_seam_mass, maxMass[upperSpecies] − lower_seam_mass)` (gravity ignores the donor
  minFlow floor, matching interior fall). The UPPER side subtracts `dm` from its y=0 cell; the LOWER side
  (computing the identical `dm` from its POS_Y halo = the upper y=0 plane) ADDS `dm` to its y=15 cell and
  adopts the species/enthalpy when it was air. Down-only (no up-fall). Keep the existing symmetric
  same-species leveling for the non-air case where neither side is "falling into the other". Gas exempt
  (handled by E2). Mirror BIT-IDENTICALLY; the seam asymmetry (donor reads live Tout, receiver reads
  halo/snapshot T) follows the existing `(2b-ii)` convention.
- Parity: extend `advection_parity_test` two-section cases — a column with water on the upper section's
  floor + air in the lower section's top → after one step the seam moved `dm` down, donor y=0 dropped,
  receiver y=15 gained, kernel == sim_engine bit-identical, seam mass conserved. Keep all existing parity
  green. `tests/run_tests.sh` green. Commit on `feat/engine-cross-section`.

## Task E2 [ENGINE] — gas buoyancy SWAP across the seam (kernel + sim_engine, bit-identical)
- Extend the `(2c)` cross-species swap to the Y seam: when the seam pair is gas/denser inverted across the
  boundary (lighter below), each side does its OWN half of the full-cell swap reading the halo — the
  UPPER cell (y=0) becomes the lower cell's content (from its NEG_Y halo) and the LOWER cell (y=15)
  becomes the upper cell's content (from its POS_Y halo). Both co-stepped ⇒ a consistent full-cell swap,
  conservative, no neighbour write. Reuse the `density()`/`swapGated()`/`SWAP_HYST` rules + the
  one-swap-per-cell claim guard, applied at the seam plane. Mirror bit-identically.
- Parity: a two-section case with gas in the lower section's top + liquid in the upper section's floor →
  after a step they swapped across the seam (gas rose, liquid sank); kernel == sim_engine; conserved.
  `tests/run_tests.sh` green. Commit.

## Task E3 [ENGINE] — horizontal (X/Z) seam wetting/fall-support (kernel + sim_engine, bit-identical)
- Lift the deferred boundary air-wetting in `(2b-i)`: across X/Z seams, a supported fluid edge cell wets
  into a real-air neighbour across the seam via the SAME antisymmetric flux (each side writes its own
  cell; donor reads its live mass, recipient adds the identical `dm`). Keep the gravity gate (a cell that
  can fall does not wet sideways). Mirror bit-identically; extend parity for an X-seam wetting case.
  `tests/run_tests.sh` green. Commit.

## Task INT [MAIN+ENGINE] — integrate, rebuild .so, native end-to-end, both loaders
- ENGINE: merge `feat/engine-cross-section` → `main`, push. Rebuild
  `JAVA_HOME=/home/claude/jdk21 ./native/build_liborge.sh`; copy to MAIN
  `core/src/main/resources/natives/linux-x64/liborge.so`; bump gitlink (`git add ORGE-ENGINE`).
- MAIN: native-backed `AuditScenarioTest`-style E2E (skips if no `.so`): drive the REAL scheduler path
  (snapshot with co-stepping → native step → batch-§9 → writeBack → reconcile) over stacked + adjacent
  sections. Assert: (a) liquid CASCADES down across Y seams to the floor (no piling on each floor),
  (b) gas RISES across a Y seam, (c) liquid SPREADS across an X seam into air, (d) thermal crosses a seam
  (a hot cell warms the neighbour section), (e) total per-species mass conserved across the whole region
  every cycle, (f) no residue/ghost. Confirm the bundled `.so` md5 matches the ENGINE build.
- `:core:test` (native) + `:fabric-1.21:build :neoforge-1.21:build` green. ONE MAIN commit (`.so` +
  gitlink + E2E), push.

## Verification
- ENGINE parity + advection suites green incl. the new seam fall/swap/wet cases (kernel == sim_engine).
- MAIN `:core:test` green incl. batch-§9 + co-stepping + native E2E; both loaders build.
- In-game (user): pour liquid across stacked seams → cascades to the floor; steam rises across seams;
  water spreads across horizontal seams; heat crosses seams; no lag (no per-cycle Java seam pass), no
  residue. Performance: engine does the work in one native batch call; dormancy preserved (only the
  active fluid region + a 1-section skirt steps).
```
