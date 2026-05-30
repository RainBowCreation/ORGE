# §10 Phase-2b — Density-driven displacement (wetting · buoyancy · liquid sorting) — Design Spec

**Status:** approved (review 2026-05-30) — ready for `writing-plans`. Supersedes the separate roadmap
items "gas buoyancy" and "liquid sorting" by unifying them under one density model. All four open
decisions ruled by the user (air-as-empty-but-gas-path-general · full-cell swap · `matOut` ABI bump ok ·
per-species §9 as one O(N) pass).

## Why this, and why now

Phase-2a shipped mass-conservative flow, but only the **settling** half: mass redistributes
**among cells that are already the same fluid**. It cannot move fluid into empty space. Two guards
in the shipped code, both confirmed, prove it:

- `orge_kernel.hpp` — every transfer (fall L108, horizontal spread L148, vertical seam L173) is
  gated `if (!isFluid(matNb) || matNb != matIx[i]) continue;`. Air is `fluid = 0`, so it is never a
  valid destination — water can't even **fall** into air, let alone spread into it.
- `MinecraftFluidReconciler` — skips any cell whose current block isn't already fluid (L81) and only
  ever does `fluid → air` (REMOVE, L95). It never places a fluid block into an air cell (`air → fluid`).

With vanilla flow suppressed by the §10 mixin, the net in-game behaviour is: **a bucket source is a
frozen single block.** Spec Decision 7 actually called for transfer "between same-material fluid cells
**and empty/air**" — the *empty/air* half was never implemented. So this is an **unfinished slice, not
a regression.**

The fix and two deferred roadmap items turn out to be **one mechanism**. `Material.defaultMass` is
already kg per 1 m³ cell — i.e. **density**:

| Material | density (`defaultMass`, kg/m³) |
|---|---|
| lava | 3100 |
| generic_solid | 2500 |
| water | 1000 |
| ice | 917 |
| **air** | **1.2** |
| **steam** | **0.6** |

If air and steam are treated as the **lightest participating fluids** and advection becomes
**density-directional + cross-material**, one rule delivers all three behaviours:

| Pair (lower / upper) | Densities | Result |
|---|---|---|
| air / water | 1.2 < 1000 (inverted) | water sinks → **falling + spreading** |
| steam / air | 0.6 < 1.2 (inverted) | steam rises → **buoyancy** |
| water / lava | 1000 < 3100 (inverted) | lava sinks → **liquid sorting** |
| lava / water | 3100 > 1000 (stable) | no swap (and §7 reacts them anyway) |

This is the missing piece that makes flow **visible** in-game, and it's more foundational than either
deferred item, so it comes before latent heat.

## Core principle (unchanged from Phase-2a)

All physics in the **native engine**. Implement in `sim_engine.hpp` first (tune in the SDL
visualizer), port **bit-identical** into `orge_kernel.hpp`, expose via JNI. Java owns only Minecraft
integration: snapshot/writeback, §9 validation, block-level reconciliation/suppression.

## Decisions (proposed — these are what I want your ruling on)

1. **Air/gas are the lightest fluids, not inert.** Every cell has a density = its material's
   `defaultMass`, including air (1.2) and steam (0.6). The "same-material-only" guard (Decision 7 of
   Phase-2a) is **relaxed**: a fluid may move into a cell that is **air OR a strictly-lighter fluid**.
   §7-reacting pairs stay excluded (Decision 5).

2. **Air-as-empty is a *specialization* of a general gas path — NOT a baked-in assumption.**
   *(RULING 2026-05-30: air-as-empty for this slice, but the model MUST keep compressible gas
   reachable for addons — ORGE is a base mod and a real gas system is a must.)* For v1 performance we
   do **not** track and advect 1.2 kg of air in every cell (that would make every cell an advection
   participant — far too expensive); air is **empty space carrying a density label**
   (`AIR_DENSITY = air.defaultMass`), and "displacing air upward" is implicit (the cell's identity
   flips). **But this is an optimization, not the model.** The general path is: a material has a
   `phase` (solid / liquid / gas) and a density; **gases can be *tracked* (own mass field, full
   advection) or *ambient* (empty, the air optimization).** Steam is already a **tracked gas** (the
   `orge:steam` block, mass field, density 0.6 < air) — so the tracked-gas code path is exercised from
   day one and air is the only ambient gas. A future **compressible-gas track** (addon-registrable
   gases with an equation of state / pressure) then drops in by flipping a gas from ambient → tracked
   and adding the EOS term — **no rearchitecture**, because the displacement rule, the per-species
   conservation (Decision 6), and `matOut` (Decision 3) already treat gas as a first-class species.
   **Hard constraint on the plan:** never special-case `air` by identity in the kernel/rule; branch on
   `phase == gas && ambient`, so an addon's tracked gas inherits buoyancy automatically.

3. **Wetting / drying — cells change material identity (the genuinely new capability).** Phase-2a never
   changes geometry. Phase-2b must: a cell's species flips **air → fluid** when fluid first enters
   previously-empty space, and **fluid → air** when its mass drains to ≈0. Because §5 `SectionStore`
   **never stores material** (material is read fresh from world blocks each snapshot), the identity
   change is realized by the **reconciler placing/removing the actual block**; the next snapshot reads
   it back. The kernel therefore must **report the final per-cell species** so Java knows what to place
   (an air cell that received water mass vs. steam mass can't be told apart by mass magnitude alone).
   → **`orgeStep` gains a `matOut[]` output** (the halo already carries `haloMat` in). `matOut` is
   transient — consumed by the reconciler in the same tick, **not persisted** (keeps §5's "no material
   in the store" invariant intact).

4. **The unified displacement rule (native physics).** Two parts, both at the advection cadence:
   - **Same-species flow (Phase-2a, kept):** fall into the cell below + viscosity-limited horizontal
     leveling, among cells of the same fluid.
   - **Cross-species displacement (new):** between two cells whose stable ordering is violated
     (lighter below heavier, beyond a hysteresis threshold), exchange contents toward the stable
     ordering — conserving **each species' mass** and **carrying temperature** (enthalpy formula,
     Decision 6 of Phase-2a). Vertical inversions → swap (drives fall, buoyancy, sorting); horizontal
     into air → wet (drives pool spreading). Air is the empty species; "swapping with air" = the denser
     fluid occupies the cell and the air label is discarded.

5. **§7 preemption stays — reacting pairs do NOT sort.** Water+lava contact is owned by §7 / the
   vanilla-whitelist (→ obsidian / stone / cobblestone), **not** density sorting. The displacement rule
   excludes reacting pairs; for the current roster the only reacting pair is water↔lava. Clean seam,
   identical in spirit to Phase-2a Decision 7.

6. **Per-species conservation (§9 upgrade) — same single O(N) pass, no perf regression**
   *(RULING 2026-05-30: ok, but implement it as one pass — performance-sensitive.)* With wetting
   (air↔water) and cross-species swaps, the §9 gate can no longer check only *total* fluid mass — a
   water→air drain mis-reported as lava would pass a total check. §9 sums mass **per species index** and
   checks each is conserved (±ε·N) and within `[0, M_full_species + ε]`. **Cost is unchanged from
   today:** keep the existing **single 4096-cell loop**, but accumulate into a tiny
   `sumBySpecies[lut.size()]` array (LUT is a handful of materials) indexed by `matIx[i]` instead of one
   scalar; at the end compare a few per-species totals. No extra grid passes, no per-species iteration —
   just a small accumulator array. It also still runs on the **background worker thread** (snapshot → bg
   step → validate → write), off the main server tick, so it's not on the hot path regardless. Air is
   untracked (no air-mass conservation). Cells that **§7 transitioned this step** are **exempt** (phase
   change is a legitimate species source/sink — water→steam removes water mass, adds steam mass),
   mirroring the existing pin/transition exemption.

7. **Reconciler grows `air → fluid` + steam placement.** `MinecraftFluidReconciler` currently refuses
   to wet an air cell (L81). Extend it: where `matOut` says a cell is now a fluid with mass>0 but the
   world block is air, **place** the representative block (water/lava/steam) at the reconciled level;
   where it's drained, **remove** (already does). `UPDATE_CLIENTS` only; keep the §7 contact whitelist.
   `FluidReconcileLogic` (pure) is unchanged except it now also drives placement, not just removal.

8. **Cross-species transfer is a FULL-CELL swap** *(RULING 2026-05-30: full-cell swap, not gradual
   fractional transfer — matches immiscible MC fluids and keeps species crisp)*: a genuine vertical
   inversion exchanges the two cells' entire contents (species + mass + temperature). **Anti-oscillation
   guards** keep full-cell swaps from "boiling" into a checkerboard: **(a)** hysteresis — swap only when
   the inversion exceeds a density-ratio threshold (equal densities never swap); **(b)** at most one
   swap per cell per advection step via an order-independent claim/flux buffer (the antisymmetric-flux
   discipline already in the kernel); **(c)** runs at the advection CFL cadence (`dt = 0.25 s`), not
   every tick; **(d)** same-species motion still uses the existing fall/spread *mass* mechanics —
   full-cell swap is reserved for genuine cross-species inversions. Tuned in the C++ reference sim + SDL
   visualizer, same workflow as Phase-2a.

9. **Cadence — reuse Phase-2a's advection pass.** Displacement folds into `PASS_ADVECTION`
   (`dt = 0.25 s`); no new cadence and (proposed) no new pass bit. Conduction unchanged.

10. **ABI / cross-repo — same dance as Phase-2a.** `orgeStep` gains `matOut[]`; `StepResult` gains a
    `material` (`char[]`) field alongside `temperature` + `mass`. Rebuild `liborge.so` via
    `ORGE-ENGINE/native/build_liborge.sh`, recommit into `core/src/main/resources/natives/linux-x64/`,
    land the rebuilt `.so` **and** the updated `NativeEngine.orgeStep` declaration **together** (ABI
    must match). `linux-x64` only; cross-platform out of scope.

## Components

### Engine (C++, `ORGE-ENGINE/` repo)
| Component | Change |
|---|---|
| `sim_engine.hpp` | add the cross-species displacement rule over `mass_kg` + species; tune buoyancy/sorting/wetting in `sim_render.hpp` (SDL). |
| `orge_kernel.hpp::step_section_with_halo` | relax the destination guard to `air OR strictly-lighter fluid`; add cross-species swap with per-species conservation; output `matOut[]`. Bit-identical to `sim_engine.hpp`. |
| `orge_jni.cpp` | pin/release the new `matOut` output array (reverse order). |
| `ORGE-ENGINE/tests/` | wetting (water spreads into air → flat pool) · buoyancy (steam column rises through air, settles at top) · sorting (lighter-below inversion resolves, no oscillation) · per-species conservation · §7-pair exclusion (water/lava never swap) · settling stability (no checkerboard over N steps). |

### Java (main repo)
| Component | Layer | Change |
|---|---|---|
| `NativeEngine.orgeStep` | core | native declaration → new ABI (`matOut` out). |
| `StepResult` | core | add `material` (`char[]`) field beside `temperature` + `mass`. |
| scheduler writeBack / reconcile plumbing | scheduler | thread `matOut` from `StepResult` to the reconciler (transient; **not** written to `SectionData`). |
| §9 mass guard | core | per-species conservation + bound; §7-transition exemption. |
| `MinecraftFluidReconciler` | MC adapter | `air → fluid` placement (incl. `orge:steam`); reads `matOut`. |
| `VanillaFluidSuppressor` | both loaders | unchanged mechanism; confirm it still whitelists §7 contact under wetting. |

No §5 change — `SectionData` stays temp+mass only; species identity stays in world blocks.

## Known risks / open questions for review

1. **Air model (Decision 2): RESOLVED** — air-as-empty *this slice*, but the gas path is general
   (ambient vs. tracked gas) so a future compressible-gas addon track drops in without rearchitecture.
   Kernel must branch on `phase == gas && ambient`, never on the `air` identity.
2. **Swap granularity (Decision 8): RESOLVED** — full-cell swap + hysteresis.
3. **`matOut` ABI (Decision 3): RESOLVED** — yes to a second breaking `liborge` ABI bump (after
   Phase-2a's `massOut`); the rebuild is native-side only (no Java build pain). The alternative (infer
   species from mass magnitude in Java) is fragile and rejected.
4. **Per-species §9 check (Decision 6): RESOLVED** — yes, implemented as the same single O(N) pass with
   a small per-species accumulator (no perf regression), on the background thread.
4. **Oscillation:** the main physics risk; mitigated by the SDL-visualizer tuning loop before any Java
   wiring, exactly as Phase-2a de-risked conduction/advection.
5. **Region edges (carried from Phase-2a Decision 12):** no-flow walls; fluid still piles at the loaded
   boundary. Unchanged limitation.

## Out of scope (deferred)

- **Latent heat** — energy plateaus on boil/freeze (separate native track; couples to §7). Next after
  this.
- **Full compressible-gas advection** (air as a tracked sloshing mass field) — the heavy alternative to
  Decision 2. **Architecture note (forward-compat):** gas is NOT a separate simulation layer — it is the
  *same* advection pass + the *same* density-swap (which already yields buoyancy for free: a light gas
  below a heavier cell is just a density inversion). The only gas-specific physics is **compressibility**,
  added as two `phase == gas`-gated terms in the same pass: (1) **expansion/diffusion** — gas spreads to
  fill all available volume / equalize instead of pooling under the liquid `M_full` capacity cap; (2) an
  **equation of state** — density from amount + temperature (hot gas lighter), coupling gas density to the
  temperature field *within the flow pass* (the conduction calc stays untouched). In Phase-2b steam is
  treated as a light *incompressible* fluid (rises via the swap, no expansion/EOS); the compressible-gas
  track flips a gas ambient→tracked and lights up those two terms — no new layer, no rearchitecture.
- **Temperature-dependent densities** (hot water/lava less dense → thermal convection) — future curve work.
- **Pressure / hydraulic head** beyond simple density ordering.
- **New non-reacting liquid pairs** (oil, etc.) — the model supports them; no new materials this slice.
- **Cross-platform native builds**; **smooth client-side fluid interpolation** (vanilla's 8 discrete
  levels at the advection cadence — steppy, not animated).
