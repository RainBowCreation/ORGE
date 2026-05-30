# §10 Phase-2b — Density-driven displacement (wetting · buoyancy · liquid sorting) — Design Spec

**Status:** approved (review 2026-05-30) — ready for `writing-plans`. Supersedes the separate roadmap
items "gas buoyancy" and "liquid sorting" by unifying them under one density model. User-ruled decisions:
air-as-empty-but-gas-path-general · full-cell swap · `matOut` ABI bump ok · per-species §9 as one O(N)
pass · **three-mass model** (`min_flow_mass ≤ default_mass ≤ max_mass`, phase = where rest sits) ·
**section-level per-pass dormancy** (decaying-cell, ported) with event wake · boil-volume over-cap deposit.

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

0. **THREE mass numbers per material — `min_flow_mass ≤ default_mass ≤ max_mass` — and the phase of
   matter is just WHERE the resting density sits between the floor and the ceiling** *(user model
   2026-05-30).* This replaces the incompressible/compressible binary entirely; you never declare
   "liquid" or "gas", you set three numbers and the behaviour falls out.
   - `min_flow_mass` = **flow floor** (cohesion / surface tension): a cell at/below this no longer donates
     to neighbours. Sets spread **extent** — `coverage ≈ total_mass / min_flow_mass`, an *emergent*
     finite spread distance with no hardcoded "7 blocks" — and the **fill-vs-pool** difference. It
     **generalises** the kernel's global `ADV_EPS_MASS = 1e-4` "treat as empty" epsilon into a per-material
     floor. **Active in Phase-2b** (it's what makes finite water spread *then stop* instead of creeping
     infinitely thin).
   - `default_mass` = **resting density + seed value** — what a cell is seeded with on section load, and
     the buoyancy resting point.
   - `max_mass` = **per-cell capacity cap** — how much packs into one 1 m³ cell before it can't compress
     further; used as the fall/merge remaining-capacity and the §9 per-cell upper bound.
   - **Liquid rests at its CEILING** (`default_mass == max_mass`, water 1000/1000; floor ~125): no
     headroom up ⇒ incompressible, **pools/merges**, stops at the floor. **Gas rests at its FLOOR**
     (`default_mass == min_flow_mass`, steam 0.6; cap above): no room down ⇒ **expands/fills** to uniform
     resting density via the ordinary spread rule, and compresses up to `max_mass` under pressure.
   - **Buoyancy/swap compares the cell's CURRENT mass** (current density), not `default_mass`: a
     *compressed* gas cell is denser than air and correctly **sinks until it expands**, then rises.
   - **Robustness — never seed a tracked gas at 0 (user crash-guard):** the seed value is always
     `default_mass`, and **`min_flow_mass > 0` is a hard invariant for gas types**, so a freshly-loaded
     gas cell always has a valid non-zero density. A 0-mass tracked-gas cell = 0 density = undefined
     buoyancy + NaN risk in the enthalpy mix `(m·T+Δm·Tₛ)/(m+Δm)`. **Air is the ambient exception:**
     untracked, density-by-label (1.2) only, never seeded.
   - **This slice:** add `min_flow_mass` + `max_mass`. `min_flow_mass` is live (water/lava floors →
     finite, stopping spread). Set `max_mass = default_mass` for **every current material** (inert until
     the compressible-gas track); the kernel capacity cap + §9 bound switch `defaultMass → max_mass` (same
     value now). Compressible gas then = a **data change** (`steam.max_mass = …` + gas spread-to-resting),
     **not** a rearchitecture — satisfying the "compressible gas is a must" requirement up front.

1. **Air/gas are the lightest fluids, not inert.** Every cell has a (current) density = its mass in the
   cell; a material's *resting* density is `default_mass` (air 1.2, steam 0.6). The "same-material-only"
   guard (Decision 7 of Phase-2a) is **relaxed**: a fluid may move into a cell that is **air OR a
   strictly-lighter (by current density) fluid**. §7-reacting pairs stay excluded (Decision 5).

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

11. **Section-level, per-pass DORMANCY (the decaying-cell method, ported) — the #1 performance
    requirement.** *(User model 2026-05-30, proven in the prior Java mod.)* Suppressing vanilla fluid
    ticking removed vanilla's single biggest fluid optimization — it stops ticking settled water — so
    **ORGE must supply its own, or a calm ocean re-simulates every cell at 4 Hz forever.** The old Java
    model used a per-**cell** decay countdown (settled → count down → at 0, drop from calc until a nearby
    block updates or temperature changes). In the native engine the **section** (4096 cells) is the unit
    of cost (snapshot + halo + dispatch + reconcile + packets are all per-section; the kernel's 4096-cell
    loop is cheap by comparison), so the decay **lifts to the section level**:
    - **Per-pass dormancy (matches the decoupled cadences).** A section is **flow-dormant** when no cell
      moved mass (`max|Δmass| < ε`) for K advection steps → skip its advection; **thermal-dormant** when
      `max|ΔT| < ε` for K conduction steps → skip its conduction. *Both* → fully asleep, dropped from the
      schedule entirely. *Either* → only the live pass runs.
    - **Near-free bookkeeping.** The reduction (`max|Δmass|`, `max|ΔT|`) piggybacks the existing post-step
      writeback loop (`cleanMass`/§9 already iterate the cells); decrement/reset the per-section, per-pass
      countdown there. The **countdown (not instant sleep) is the hysteresis** that stops cells near
      equilibrium from flickering active↔dormant. K is tunable in the audit.
    - **Event-driven WAKE (the one genuinely new integration piece).** Once dormant, a section is **no
      longer snapshotted**, so the §round-2 snapshot-diff (`CellMaterialTracker`) cannot notice changes —
      dormancy *requires* an explicit wake. Wake triggers (must be exhaustive — a missed trigger = stale
      frozen fluid, the classic dormancy failure mode):

      | Trigger | Mechanism |
      |---|---|
      | block placed/broken/changed (incl. bucket) | block-update event → mark section active |
      | temperature source added/removed (B+C pins) | source-roster change → wake the section |
      | neighbour pushes flux across the shared seam | an active section with nonzero boundary-face flux wakes the adjacent section (lets flow propagate INTO sleeping regions — without it flow stops dead at a dormant border) |
      | section newly enters player range | starts active (one step to settle) |

    - **Scheduler shape change.** `world.snapshot(range)` becomes *"snapshot the **active set** within
      range"*, not the whole sphere — the sim becomes **event-driven**: edits/new-sections inject into the
      active set, settled sections fall out. This is the change that makes large worlds cheap.
    - **Cell-level decay is a noted SECONDARY optimization** — skipping settled cells inside an *active*
      section's kernel inner loop. Lower value here (the inner loop is already cheap); add only if
      profiling shows mostly-still active sections cost. Section-level captures ~95%.

12. **Phase-change mass↔volume accounting (the boil-volume landmine).** Boiling a full water cell
    conserves mass (1000 kg water → 1000 kg steam) but **not volume**: 1000 kg of steam at steam's
    resting density (0.6) wants ~1667 cells, and even at steam's `max_mass` cap it cannot sit in one cell.
    So §7 phase-change cannot simply swap the block in place at full mass. **Decision:** phase change is a
    mass-preserving **species conversion** that deposits the converted mass into the cell **over-cap if
    necessary** (a transiently compressed gas parcel), and the **advection pass relieves it on the next
    steps** via the ordinary expansion/spread rule (gas relaxing toward `default_mass`). The §9 gate must
    therefore **exempt §7-transitioned cells from the per-cell `max_mass` bound for that step** (it already
    exempts them from the conservation sum, Decision 6) — otherwise `cleanMass` clamps the fresh steam and
    destroys mass. This couples §7 ↔ the mass model and is shared with the latent-heat track; Phase-2b
    implements the *exemption + over-cap deposit*, full vaporization energetics stay in latent heat.

13. **Performance notes (orchestration, not the per-cell rule).** The displacement rule is a handful of
    comparisons per cell — cheap. The real costs are per-section orchestration, addressed by:
    **(a)** dormancy/active-set (Decision 11); **(b)** **reconcile throttle** — the reconciler only writes
    a block when the mass crosses into a different render **level bucket** (mass can move every step
    without a packet), and block writes stay `UPDATE_CLIENTS`-only; **(c)** **scratch-buffer reuse** on the
    worker thread (pool the `temp[]`/`mass[]`/`matOut[]` arrays) so a 4 Hz cadence doesn't churn the GC.

## Components

### Engine (C++, `ORGE-ENGINE/` repo)
| Component | Change |
|---|---|
| `sim_engine.hpp` | add the cross-species displacement rule over `mass_kg` + species; tune buoyancy/sorting/wetting in `sim_render.hpp` (SDL). |
| `orge_kernel.hpp::step_section_with_halo` | relax the destination guard to `air OR strictly-lighter fluid`; add cross-species swap with per-species conservation; output `matOut[]`. Bit-identical to `sim_engine.hpp`. |
| `orge_jni.cpp` | pin/release the new `matOut` output array (reverse order). |
| `ORGE-ENGINE/tests/` | wetting (water spreads into air → flat pool, **stops at `min_flow_mass`**, coverage ≈ mass/floor) · buoyancy (steam column rises through air, settles at top) · sorting (lighter-below inversion resolves, no oscillation) · per-species conservation · §7-pair exclusion (water/lava never swap) · settling stability (no checkerboard over N steps) · **settle-detection** (a settled field reports `max|Δ| < ε` so the section can sleep) · boil-volume (an over-cap steam parcel expands toward `default_mass` over K steps without losing mass). |

### Java (main repo)
| Component | Layer | Change |
|---|---|---|
| `NativeEngine.orgeStep` | core | native declaration → new ABI (`matOut` out). |
| `StepResult` | core | add `material` (`char[]`) field beside `temperature` + `mass`. |
| scheduler writeBack / reconcile plumbing | scheduler | thread `matOut` from `StepResult` to the reconciler (transient; **not** written to `SectionData`). |
| §9 mass guard | core | per-species conservation + bound; §7-transition exemption (incl. over-cap, Decision 12). |
| `MinecraftFluidReconciler` | MC adapter | `air → fluid` placement (incl. `orge:steam`); reads `matOut`; level-bucket reconcile throttle (Decision 13). |
| `VanillaFluidSuppressor` | both loaders | unchanged mechanism; confirm it still whitelists §7 contact under wetting. |
| **dormancy/active-set** (Decision 11) | scheduler (core, pure) | per-section per-pass settle countdown + active-set; `world.snapshot` steps the active set, not the whole range sphere. Pure + unit-tested (mirrors §9/MassSnapshot). |
| **wake hooks** | both loaders | block-update + bucket events → mark section active; seam-flux + new-in-range wake. `ExpectPlatform`, behind a pure `WakeSink` seam. |
| **scratch-buffer pool** | scheduler | reuse `temp[]`/`mass[]`/`matOut[]` on the worker thread (Decision 13c). |

No §5 change — `SectionData` stays temp+mass only; species identity stays in world blocks. The dormancy
countdown is scheduler-side state (active-set), not persisted in §5.

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
5. **Dormancy (Decision 11): RESOLVED** — section-level per-pass decay + event-driven wake (the user's
   proven decaying-cell method, grain lifted cell → section). The risk is a **missed wake trigger** →
   stale frozen fluid; mitigated by an exhaustive, unit-tested wake-trigger set.
6. **Boil-volume (Decision 12): RESOLVED** — phase change deposits converted species mass over-cap and
   the advection pass expands it; §9 exempts §7-transitioned cells from the per-cell bound that step.
7. **Oscillation:** the main *physics* risk; mitigated by the SDL-visualizer tuning loop before any Java
   wiring, exactly as Phase-2a de-risked conduction/advection.
8. **Cadence interaction (advection 4 Hz vs §7 phase change 1 Hz):** water+lava can sit adjacent for up
   to 20 ticks before §7 reacts them to obsidian. Acceptable (vanilla obsidian isn't instant either), but
   the C++ tests must confirm the swap/spread does nothing pathological in that window.
9. **Region edges (carried from Phase-2a Decision 12):** no-flow walls; fluid still piles at the loaded
   boundary. The round-3 **cross-seam §9 transient** (per-section closed-wall gate vs. real kernel seam
   flux) becomes **more frequent under wetting**; the deferred fix (batch-level Σ, or engine-reported seam
   flux) moves up the priority list — flagged, not yet scheduled into this slice.

## Testing (Java side; C++ in the Components table)

- **Dormancy/active-set (pure unit):** a settled section's countdown reaches 0 and it leaves the active
  set; a wake trigger (block edit / source change / seam flux / new-in-range) re-adds it; per-pass
  independence (flow-dormant but thermal-active still conducts). Missed-wake is the failure mode → cover
  every trigger.
- **§9 per-species + boil-volume exemption (pure unit):** per-species conservation; a §7-transitioned
  over-cap cell is exempt from the bound that step (no mass destroyed).
- **`FluidReconcileLogic` (pure):** `air → fluid` placement levels; level-bucket throttle (mass moves
  within a bucket → no write).
- **Integration (headless `AuditScenarioTest`, new `liborge`):** water above a gap falls + spreads into
  air + stops finite; steam rises; water next to lava still steams/obsidians via §7; a settled pool
  sleeps then wakes on an edit.

## Out of scope (deferred)

- **Latent heat** — energy plateaus on boil/freeze (separate native track; couples to §7). Next after
  this. Phase-2b lands only the boil-volume *exemption + over-cap deposit* (Decision 12), not the energetics.
- **Full compressible-gas advection** — the follow-on track, **already de-risked by Decision 0's
  `default_mass`/`max_mass` split**. Gas is NOT a separate simulation layer: it is the *same* advection
  pass + the *same* density-swap (buoyancy is free — a light gas below a heavier cell is just a density
  inversion) + the *same* horizontal spread (which, given gas's low resting `default_mass`, thins gas out
  to **fill volume** = expansion, no new code). What the compressible-gas track adds on top is mostly
  **data + one behavior**: (a) set `steam.max_mass > default_mass` so gas has headroom to pack denser
  (Decision 0); (b) make the gas spread relax *toward `default_mass`* (fill/equalize) rather than pool
  under a cap; (c) optionally an **equation of state** so resting density falls with temperature (hot gas
  lighter), coupling gas density to the temperature field *within the flow pass* (the conduction calc
  stays untouched). In Phase-2b every material keeps `max_mass == default_mass` (steam included), so steam
  behaves as a light fluid that rises via the swap; the compressible-gas track lights up (a)–(c) — **no
  new layer, no rearchitecture.**
- **Temperature-dependent densities** (hot water/lava less dense → thermal convection) — future curve work.
- **Pressure / hydraulic head / connected-vessel equalization** beyond simple density ordering —
  **explicit scope decision:** water falls + spreads but will **not climb** to "find its level" up the far
  side of a U-tube (no hydrostatic pressure term). This matches vanilla (vanilla water doesn't climb) and
  is a deliberate omission, not a bug.
- **New non-reacting liquid pairs** (oil, etc.) — the model supports them; no new materials this slice.
- **Cross-platform native builds**; **smooth client-side fluid interpolation** (vanilla's 8 discrete
  levels at the advection cadence — steppy, not animated).
