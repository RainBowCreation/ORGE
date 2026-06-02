# Placement as Displace-and-Inject (conservative replace transactions)

**Date:** 2026-06-02
**Status:** Design — pending spec review
**Builds on:** `2026-06-01-unified-fluid-engine-design.md` (ONE-substance advection, Pass A/B/B′),
`2026-05-31-molar-mass-gas-displacement-design.md` (air is a conserved molar gas),
`2026-06-02-time-based-unified-flow-design.md` (combined every-5-tick async step).
**Does NOT change:** natural advection (Pass A/B/B′) — see Non-Goals.

## Problem

Placing a fluid (bucket, `/setblock`, piston, dispenser) **bypasses the engine**. The Java path
(`MaterialChangeReseed.apply`, `core/.../scheduler/MaterialChangeReseed.java:85-91`) detects the
material change and **clears the incumbent cell's stored mass to 0** — then `ColumnAssembler`
(`:66`) seeds the new species' `defaultMass`. Two defects fall out of that bypass:

1. **Non-conservative.** The cell almost always already holds **air** (`orge:air`, ~1.2 kg) — a
   *real, conserved molar gas* in the unified model (§11). Clearing it to 0 **deletes** that gas.
   Every placement leaks the very mass the molar-gas work is careful to conserve. Physically wrong
   too: water should *shove air aside*, not annihilate it.

2. **Vanish race ("appears, then disappears").** Because the placement is a bare block-edit
   racing the async engine, a background step **snapshotted before** the placement finishes later
   and writes its **stale** result back: `MinecraftThermalWorld.writeBackColumn` arraycopies the
   stale mass over the store (`:393-396`) and the reconciler renders the stale species, stomping
   the freshly-placed block to air (`MinecraftFluidReconciler.java:130-135`). Confirmed in-game:
   *appears then vanishes, anywhere including still ground.* Wake-on-place does not help — the
   stale write-back stomps the **live** block, and the next snapshot then reads air, so the intent
   is gone entirely.

The engine **already conserves** the incumbent when fluid flows in *naturally* during a step:
Pass A (molar sort) and Pass B′ (into-lighter displacement) buoy the lighter air out
antisymmetrically (`sim_engine.hpp` Pass B′, the `BAccum` ledger). Placement simply never uses
that machinery.

## Goal

Make a placement a **first-class engine transaction**: a per-step **list of injections**, each of
which (a) places the requested species/mass at its target cell, (b) **displaces the incumbent** to
an escape neighbour using the existing molar/buoyancy push (conserved when possible), and (c) is
**consumed atomically inside** the engine's snapshot→step→write-back. Consequences:

- **Conservation** — the displaced incumbent is relocated, not deleted.
- **It IS the push** the engine has always had (Pass B′), finally used at placement time.
- **The vanish race dies for free** — the placement is a durable *engine input*, not a racing
  block-edit, so a stale background step cannot lose it. No separate skip-guard needed.

## Non-Goals (YAGNI)

- **No change to natural advection.** Pass A/B/B′ stay exactly as-is. The injection pre-pass
  *reuses* B′'s escape search but is a separate, **authoritative** operation that runs *before* the
  passes.
- **No new species, no new blocks.** Water / lava / air / steam as today.
- **No catch-up/dt coupling.** Injections apply in-step at the current `dt`; they do not feed the
  `MAX_CATCHUP` accumulator (rejected: a global catch-up dt would make a fresh placement "jump"
  two steps — see the time-based spec's dt discussion).
- **Solids stay §7's.** Injection is for **movable** incumbents. A non-movable incumbent
  (stone/ice/void) is not displaced — the new species just seeds (existing path).
- **No optimistic-skip guard.** An earlier candidate (skip a column's write-back when its live
  species changed since snapshot) is **superseded** by injection durability (Part B2) and is not
  built. It is recorded here only so a future reader knows it was considered and why it was dropped
  (it loses mass on same-material placement; injection does not).

## The crux: injection is authoritative, natural flow is conservative

Both use the **same** escape search (molar/buoyancy). They differ **only** in the no-escape branch:

| incumbent… | **can** escape | **cannot** escape (sealed by higher-molar / full neighbours) |
|---|---|---|
| **Injection** (placement — must honour player intent) | displace incumbent to escape, then place — **conserved** | **replace anyway**: place the species; incumbent mass is **lost** (bounded, logged sink) |
| **Natural flow** (engine advection) | displace incumbent, flow in — **conserved** | **no-op**: flow does not happen, source stays put — **CURRENT behaviour, unchanged** |

Injection = *best-effort displace, fallback replace*. Natural flow = *displace or don't move*.

---

## Part A — Engine: injection list + displace pre-pass

### A1. Injection record + list input

A struct passed into the world step (one per placement this step):

```
struct Injection {
    int   cellIndex;     // full-height column index (x + 16*y + 6144*z), same space as matIx
    int   columnId;      // which (cx,cz) column in the batch (index into the step's column list)
    char  species;       // LUT index of the species to place (movable)
    float mass;          // kg to place (the legitimate bucket/source seed, e.g. water 1000)
    float temperature;   // K (material default_temperature, or current ambient)
};
```

The step receives a `count` + flat arrays (A5). An empty list ⇒ today's behaviour exactly.

### A2. Displace pre-pass — runs ONCE, before Pass A/B/B′

For each injection `inj` (in the deterministic order of A3):

```
inc = cell[inj.cell]                       // the incumbent (species, mass, T)
if inc.species is non-movable OR inc.mass == 0:
    cell[inj.cell] = {inj.species, inj.mass, inj.T}   // nothing to displace: plain place
    continue
esc = find_escape(inj.cell, inc)           // reuse Pass B′ escape search: molar/buoyancy,
                                           //   lighter incumbent buoys UP, else sideways/down;
                                           //   deterministic escape-direction hash
if esc found:
    relocate(inc → esc)                    // antisymmetric: esc gains inc.mass@inc.T (merge if
                                           //   same species, adopt if vacuum); inc.cell emptied
    cell[inj.cell] = {inj.species, inj.mass, inj.T}     // CONSERVED
else:
    record_sealed_loss(inc.species, inc.mass)           // logged sink (A4)
    cell[inj.cell] = {inj.species, inj.mass, inj.T}     // REPLACE anyway (player intent wins)
```

After the pre-pass consumes the whole list, the normal `advect_world` passes run on the resulting
world, so a freshly-placed cell **flows the same step** (no extra latency).

`find_escape`/`relocate` are the **existing B′ helpers** (the molar buoyancy rule, the
deterministic escape-direction hash, the antisymmetric `BAccum` merge/adopt). They are factored so
the pre-pass and Pass B′ call the same code → no behavioural drift, `sim_engine`/kernel parity
preserved.

### A3. Concurrent-injection claim buffer (multiple placements per step)

Many blocks can be placed in one 5-tick window, so the list can touch adjacent cells. Apply the
list **atomically and deterministically**:

- **Order:** ascending `(columnId, cellIndex)` — stable, reproducible, loader-independent.
- **Per-cell CLAIM buffer** (same idea Pass A/B′ already use): a cell may be **written by at most
  one** injection event (as a target) and **receive at most one** relocation (as an escape) per
  step. Conflicts resolve by the order above:
  - Two injections targeting the **same** cell → the first wins; the second is dropped (logged).
  - Injection X's escape target is injection Y's **target** cell → X must not relocate into a cell
    Y is about to overwrite; X re-searches for another escape, else takes the sealed-loss branch.
  - Two injections choosing the **same** escape cell → the second re-searches, else sealed-loss.

This keeps the pre-pass a permutation-plus-bounded-sink ⇒ conservation provable per A4.

### A4. Conservation / §9 ledger accounting

The region §9 ledger (`StepValidator.SpeciesMassLedger`, `Scheduler.java:275-284`) compares
per-species before/after sums and HOLDs the whole region on a mismatch. Injection deliberately
changes those sums, so the ledger must be **told**, or every placement would HOLD the region:

- **Injected species (source):** `+inj.mass` is an *allowed source* — identical in spirit to the
  one legitimate `defaultMass` seed the ledger already tolerates. The step reports
  `Σ injected_mass[species]` as an expected positive delta.
- **Sealed-loss incumbent (sink):** `−inc.mass` in the no-escape branch is an *allowed, logged
  sink*. The step reports `Σ sealed_loss[species]` as an expected negative delta.
- The ledger's conservation test becomes, per species:
  `after == before + injected[species] − sealed_loss[species]` (within the existing epsilon).
- A successful **displace** is mass-neutral (antisymmetric) ⇒ contributes nothing to either term,
  so the ordinary conserved path is unchanged.

These two reported deltas travel back with the step result so the **Java** ledger (which runs the
gate) can apply them; the engine computes them, the scheduler trusts them. (Mechanism in B3.)

### A5. ABI

Extend `orgeStepWorld` with the injection channel — additive, so the per-cell arrays are
unchanged:

```
double orgeStepWorld(
    ... existing args (nCols, cx[], cz[], matIx[], mass[], tIn[], LUT[], passes, dt,
                       tOut[], massOut[], matOut[]) ...,
    int      injCount,        // 0 ⇒ today's behaviour
    int[]    injColumn,       // injCount entries
    int[]    injCell,
    char[]   injSpecies,
    float[]  injMass,
    float[]  injTemp,
    float[]  ledgerOut)       // [2*nSpecies]: injected[] then sealedLoss[], for the §9 gate
```

`injCount == 0` is the fast path (no pre-pass). The injection apply + `find_escape`/`relocate`
helpers live in the **shared header** (`orge_kernel.hpp`) so `sim_engine.hpp` and any kernel path
stay **bit-identical**. `ledgerOut` is the only new *output* and is zero when `injCount==0`.

---

## Part B — Java: enqueue placements, feed the engine, durability

### B1. Capture placements as injection intents (not reseed-deletes)

Today `MaterialChangeReseed` clears the incumbent mass for **every** changed movable cell. Split
that responsibility:

- **Movable incumbent + movable new species (the displacement case):** do **not** clear/seed in
  Java. Instead **enqueue an injection intent** `{dim, cell, species, defaultMass, temp}` (B2). The
  engine now owns both the seed and the incumbent's fate.
- **Everything else `MaterialChangeReseed` did keep:** non-movable incumbent → movable (e.g.
  `/setblock water` over stone — no fluid mass to conserve), and broken-block → air (solid → gas,
  the incumbent had no fluid mass). These keep the **existing** clear-to-0 + single
  `ColumnAssembler` seed (no displacement needed). Temperature reseeding is unchanged in all cases.

The "exactly one mass seed in the pipeline" invariant is preserved: a displacement placement's seed
now lives in the **engine injection** instead of `ColumnAssembler`; non-displacement seeds stay in
`ColumnAssembler`. The two paths are mutually exclusive per cell.

### B2. Pending-injection queue (the race-killer)

A server-thread-confined `PendingInjections` map keyed by `(dim, cell)`:

- The block-change/wake hook (`WakePlatform.registerBlockChangeWake`, already firing on
  place/piston/`/setblock`) **records an intent** when the change is a movable-into-movable
  placement (B5 filters out the reconciler's own writes).
- Intents **persist until a step consumes them**, then are cleared. Because the queue is the source
  of truth — not the racing block-edit — **any number of stale in-flight steps cannot lose a
  placement**: it simply rides the next step that covers its cell. This is what makes the
  optimistic-skip guard unnecessary (Non-Goals).
- An intent for a cell that is later overwritten by a *newer* intent (same key) is replaced
  (last-write-wins), matching what the player sees.

### B3. Snapshot/marshal: drain the queue into the step's injection list

In `snapshotColumns` (after assembling columns), for each column in the batch, **drain** the
queued intents whose cell lies in that column into the step's injection arrays (A5); leave
out-of-region intents queued. After the step returns, apply `ledgerOut` (A4) to the §9 gate and
**clear** the drained intents (only on a successful, non-held write-back — a HELD region keeps them
queued for the next try).

### B4. Reconciler — unchanged decision, no skip-guard

`MinecraftFluidReconciler` already renders each cell from the engine **output** species/mass, and a
displaced incumbent now has a real post-step home, so the reconciler naturally paints both the
placed cell and the cell the incumbent moved to. No change beyond consuming the same `matOut`. The
optimistic-skip guard is **not** added (superseded by B2).

### B5. Distinguish reconciler self-writes from player edits (reuse existing machinery)

The reconciler calls `setBlock` (UPDATE_CLIENTS) to paint fluids; those must **not** enqueue
injections (they are engine output, not player intent), or a flowing column would re-inject
forever. Reuse the existing signature machinery (`CellMaterialTracker` records the engine **output**
species — the same fix that stopped `MaterialChangeReseed` misfiring on reconciler writes): the
wake hook enqueues an intent **only** when the new live species differs from the recorded
engine-output species for that cell. A `reconciling` re-entrancy flag set across `writeBackColumn`
is the belt-and-suspenders guard.

---

## Testing (test-first)

**Pure-Java (`:core:test`, fast path — no native lib):**

1. **Intent queue durability (race regression):** enqueue a placement intent; run N "stale" step
   cycles whose snapshots predate it; assert the intent is still queued and is drained into the
   (N+1)th step's injection list — i.e. *appears then never vanishes*.
2. **B1 routing:** movable→movable placement enqueues an injection and does **not** clear mass in
   `MaterialChangeReseed`; solid→air break and non-movable→movable still use the existing
   clear+seed (no injection).
3. **B5 self-write filter:** a reconciler `setBlock` matching the recorded engine-output species
   enqueues **no** intent; a genuine player change does.
4. **Ledger application:** given `ledgerOut = {injected, sealedLoss}`, the §9 gate accepts
   `after == before + injected − sealedLoss` and still HOLDs a genuine fabrication.

**Engine (`tests/`, against the conservation oracle):**

5. **Conserved displace:** inject water into an air cell with a free neighbour → water placed at
   target; air relocated (buoyed up); total air mass `== pre-injection air total` (oracle exact).
6. **Sealed-loss replace:** inject water into an air cell sealed by higher-molar/full neighbours →
   water placed; air mass lost; `sealedLoss[air] == 1.2`; ledger NOT held.
7. **Same-material top-up (the case that killed the skip-guard):** inject water into an existing
   water cell → placed mass honoured, no mass *lost* to a stale overwrite, conserved.
8. **Concurrent injections (A3 claim buffer):** a list touching adjacent cells (incl. one
   injection's escape == another's target) applies deterministically and conserves; duplicate
   targets resolve first-wins (logged).
9. **Natural-flow no-escape UNCHANGED:** an engine advection step where a flow target has no escape
   still **no-ops** (bit-identical to current) — proves injection's authoritative branch did not
   leak into natural flow.
10. **`sim_engine` / kernel parity:** shared-header inject/escape helpers keep both paths
    bit-identical (existing parity suite green; `injCount==0` ⇒ byte-for-byte today).

## Risks / mitigations

- **Ledger false-HOLD on every placement** — the subtlest failure. Mitigated by test 4 + 6 (the
  gate must accept the reported source/sink) and by `injCount==0 ⇒ ledgerOut==0`.
- **Claim-buffer conflicts / non-determinism** — test 8 pins deterministic order + conservation.
- **Losing the single-seed invariant** — test 2 pins exactly-one-seed (engine for displacement,
  `ColumnAssembler` otherwise; mutually exclusive).
- **Reconciler re-injection loop** — test 3 + the `reconciling` flag (B5).
- **ABI/parity break** — additive ABI + `injCount==0` fast path + test 10.
- **Sealed-loss surprises** — bounded (one incumbent dose) and logged; acceptable per the agreed
  crux table.

## File touch list (anticipated)

- `ORGE-ENGINE/orge_kernel.hpp` — `Injection` struct; `find_escape`/`relocate` factored as shared
  helpers; the displace pre-pass; the `ledgerOut` accumulation.
- `ORGE-ENGINE/sim_engine.hpp` — call the shared pre-pass before `advect_world`; ensure Pass B′
  uses the same factored helpers (no drift).
- `ORGE-ENGINE/orge_jni.cpp` — thread the injection arrays + `ledgerOut` through `orgeStepWorld`.
- `core/.../engine/NativeEngine.java` + `OrgeEngine` (+ `ColumnTask`/`ColumnResult` or a sibling
  carrier) — injection params on the step call; `ledgerOut` on the result.
- `core/.../scheduler/PendingInjections.java` — **new**: server-thread queue keyed by `(dim,cell)`.
- `core/.../scheduler/MaterialChangeReseed.java` — split: enqueue intent for movable→movable;
  keep clear+seed for the rest.
- `core/.../scheduler/MinecraftThermalWorld.java` — drain the queue in `snapshotColumns`; apply
  `ledgerOut` + clear-on-success in the write-back; `reconciling` flag.
- `core/.../scheduler/Scheduler.java` — feed `ledgerOut` into the §9 gate
  (`StepValidator.SpeciesMassLedger`).
- `.so` rebuild + gitlink bump.
- Tests across `:core` (`:core:test`) and the engine `tests/`.
