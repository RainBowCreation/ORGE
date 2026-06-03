> **Material-table model (current, 2026-06-03)** — see
> `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is **engine-resident** (register-once via
> `orgeRegisterMaterials`), selected per step by `lutEpoch`, NOT shipped per `orgeStepWorld` call. The
> walkthrough below reflects this; any older "per-step / batch-local / first-seen LUT" phrasing has been
> corrected.

# ORGE step — what happens from "server decides what to simulate" to the next T

This walks the whole-region physics step end to end: how the server decides which blocks to load and
simulate, what it hands the native engine, what the engine does to reach the next state, and how that state
is written back. It describes the architecture as it actually runs after the 2026-06-01 whole-region pivot
(`docs/superpowers/specs/2026-06-01-whole-region-engine-step-design.md`).

**One-line mental model:** every cadence tick, the server gathers the *awake* chunk columns near players
(plus a one-column loaded "apron"), packs each as a **full-height column** of cells, hands the whole set to
the engine as **one `World`**, the engine steps that World once to the next instant, and the server writes
the result back into its stores and the Minecraft blocks — or throws the whole cycle away if mass didn't
balance.

Key sizes/!constants: a column is `16 × 384 × 16 = 98 304` cells (`CHUNK_N`), engine cell index
`idx = x + 16·y + 6144·z` with `y ∈ [0,384)`; Minecraft `Y` maps in as `yEngine = yWorld + 64`. A vanilla
section is `16³ = 4096` cells; a column is 24 stacked sections (`sectionY ∈ [-4, 19]`).

---

## Step 0 — The clock: what triggers a step

The scheduler is driven off the server post-tick event (`Scheduler.onServerTick`). It runs **two
independent cadences** on the 20-ticks-per-second grid (`Scheduler.java`):

- **Conduction** every `TICKS_PER_STEP = 20` ticks → once per second, `dt = 1.0 s` (`PASS_CONDUCTION`). Heat
  diffusion only.
- **Advection** every `ADVECTION_TICKS = 5` ticks → 4× per second, `dt = 0.25 s` (`PASS_ADVECTION`). Bulk
  mass movement (fluids/gas).

At a cadence boundary the scheduler, if idle, submits **exactly one** off-thread job (single-in-flight). It
is **stateless per call**: the server owns the truth (`SectionStore`), the engine holds nothing between
calls. Everything below is one cycle.

---

## Step 1 — What even exists: chunk load decides the universe of data

The server's vanilla chunk loading decides what physics data exists at all. Chunk load/unload hooks
(`SectionStorePlatform.registerChunkHooks` → `SectionStoreManager.onChunkLoad/Unload`) call:

- `SectionStore.loadColumn(cx, cz)` on chunk load — materialises that column's per-cell temperature/mass
  from the compressed `world/orge/` region file (or ambient if never simulated).
- `SectionStore.unloadColumn(cx, cz)` on unload — flushes + drops it.

So at any instant the **loaded set** = the columns Minecraft currently has loaded. `SectionStore` stores
per **section**, each either `UNIFORM` (one T + one mass, the common calm/ambient case) or `FULL`
(per-cell `float[4096]`). Material identity is **not** stored — it is always derived live from the world
block via `MaterialBindings`. This matters for the three-way taxonomy the engine relies on:

| State | How it's represented | Meaning to the engine |
|---|---|---|
| **Unloaded** | the column isn't in the World we send (`findChunk → null`) | **no-flow wall** — mass can't cross into the unknown |
| **Vacuum** | a present cell, `matIx = 0` (void), `mass ≈ 0` | a real empty cell fluid/gas may flow into |
| **Air** | `matIx = orge:air`, `mass ≈ 1.2 kg`, `airFlag` | real finite gas to **displace**, not absorb |

Because Minecraft loads a column **full height**, a loaded column has *every* cell defined — "unknown" can
only ever mean an absent column, so vacuum and unloaded never collide.

---

## Step 2 — Deciding WHAT to simulate this cycle (`snapshotColumns`)

The engine never steps the entire loaded set — only the **active region**. On the server thread,
`MinecraftThermalWorld.snapshotColumns(range)` builds it (`range` is auto-tuned, Step 7):

1. **Player-sphere union.** Across every loaded level, take the sphere of sections around each player
   (`SphereUnion.expand(anchors, range)`), plus forced/anchored chunks. This is the §10 dormancy gate: a
   fully-settled section contributes nothing; a section that's still changing (or newly in range, or just
   woken by a block edit) is "active".
2. **Project to awake columns.** Any active section ⇒ its whole `(cx,cz)` column is awake.
3. **Add the loaded apron.** Expand by **one ring of LOADED neighbour columns** (`±1` in X and Z). This is
   the replacement for the old `SeamCoStep`: a loaded-but-dormant neighbour gets stepped as a *real* column
   so flow at the region edge isn't bounced off a false wall. A genuinely MC-unloaded neighbour is
   **excluded** → correctly an absent-column wall. (Columns unloaded since selection are skipped.)

The result is the set of `(cx,cz)` columns to step this cycle.

---

## Step 3 — Assembling each column (`ColumnAssembler`)

For each chosen column, `ColumnAssembler.assemble(cx, cz, lut, src)` builds three `CHUNK_N`-length arrays
(`matIx`, `mass`, `temperature`) in engine index order, walking all 24 sections (`sectionY -4..19`). Per
cell it maps section-local `si = x + 16·sy + 256·z` → column `colIdx = x + 16·(sectionY·16 + sy + 64) +
6144·z`, and fills:

- **`matIx`** — from the **live block** via `MaterialBindings` (→ its **stable global slot** in the
  published material table). Empty/unstored ⇒ `orge:air`.
- **`temperature`** — stored T (or biome ambient).
- **`mass`** — stored mass, **except** the one legitimate seed: a cell that is a fluid *and* whose stored
  mass is `≤ 0` *and* whose **prior species ≠ its current species** is seeded once to `defaultMass`.

That last clause is the **signature gate**. `prior species` is the engine's *output* species recorded for
that cell last cycle (`recordCellMaterials` → `CellMaterialTracker`). It distinguishes a genuine new
placement (a player just placed water: prior was air/void, so seed it to 1000 kg) from an **engine-drained**
cell (the engine moved the water out but the cell is still labelled water at 0 kg: prior already == water,
so **do not** re-seed). Without this gate a drained cell would be refilled to full mass every cycle —
"mass from nothing".

Output of this step: a `List<ColumnTask>` (one full-height column each), tagged with the `lutEpoch` that
selects the **engine-resident** material table. The LUT itself is no longer rebuilt or shipped per step.

---

## Step 4 — Marshalling to flat arrays (`RegionMarshaller`)

`RegionMarshaller.flatten` packs the columns into the contiguous primitive arrays the JNI expects:
`cx[]`, `cz[]`, and `matIx/mass/tIn` each `nCols · CHUNK_N` long (column-major). The per-material LUT is
**not** packed per step — it is registered **once**, separately, whenever the material set changes
(load/`/reload`): `NativeEngine.registerMaterials(lutEpoch, table)` → `LutArrays.pack` → the native
`orgeRegisterMaterials`, shipping the **six** physics floats per slot (conductivity, heat capacity, molar
mass, `minMass`, `maxMass`, viscosity). There is no separate movability / fluid / gas / air flag —
immovability is `visc == +∞`, buoyancy falls out of molar mass, and slot 0 is the VACUUM sentinel (the
lightest *movable* fluid: `molar/minMass/maxMass == 0` with finite viscosity).

`NativeEngine.stepWorld(columns, lutEpoch, dt, passes)` hands the column data to the native
`orgeStepWorld`, which selects the already-resident table by `lutEpoch`. (If the native lib is absent,
`StubEngine` echoes inputs so headless tests still run.)

---

## Step 5 — The native step (`orgeStepWorld`): reaching the next T

This is the heart. In one JNI call (`orge_jni.cpp`), pinning the arrays via `GetPrimitiveArrayCritical`:

1. **Build a transient `World`.** Select the **resident** material table by the supplied `lutEpoch`, then for each column `ensureChunk(cx,cz)`
   and copy its `matIx/mass/T_curr` straight in. Set `sectionLoaded[*] = 1` for all 24 sections so every
   section actually steps (full-height). The World is a sparse map of columns keyed by `(cx,cz)`; an absent
   key is the no-flow wall from Step 1.
2. **Conduction** (if `passes & PASS_CONDUCTION`): `compute_frame_to_backbuffers(world, dt)` computes each
   cell's next temperature into the back buffer (harmonic-mean conductivity, forward-Euler, reads neighbour
   chunks' `T_curr` live across X/Z), then `swap_all_backbuffers` publishes it. Mass/material untouched.
3. **Advection** (if `passes & PASS_ADVECTION`): take **one pre-advection `WorldSnapshot`** of the whole
   assembled World (`snapshot_world`), then `advect_chunk(world, chunk, mats, &snap)` for every column. The
   snapshot is what makes **cross-column X/Z** flow correct and order-independent (without it, X/Z chunk
   seams are a no-flow wall). Inside `advect_chunk`, for each column the engine runs the §10/§11 passes:
   - **fall** — gravity pulls liquid down (interior to a section);
   - **horizontal spread / level** — liquid evens out, viscosity- and `minFlow`-limited;
   - **cross-seam flow** — across the old 16-block section boundaries (Y) and across chunk boundaries (X/Z),
     using the snapshot so a donor's debit equals the receiver's credit (antisymmetric → conservative);
   - **liquid-displaces-gas** — liquid sinks into a lighter finite-air cell and the air rises (the
     un-banked cross-seam-into-air cases, vertical *and* horizontal);
   - **gas volume-fill / buoyancy** — gas expands into vacuum and lighter-over-denser swaps.
   Every pass writes only its **own** cell and derives its transfer from the shared snapshot, so each
   species' mass is conserved by construction.
4. **Read back.** Copy each column's `matIx / mass_kg / T_curr` (post-swap, post-advect) into the output
   arrays, release the arrays (outputs copied back), return elapsed milliseconds.

This whole call runs **off the server thread** (the scheduler submitted it as a job), so the server keeps
ticking while physics computes.

---

## Step 6 — Validate: one region-wide conservation ledger (commit or HOLD)

Back on the server thread, `RegionMarshaller.slice` turns the flat outputs into a `List<ColumnResult>`.
Before anything is persisted, the scheduler runs **one `SpeciesMassLedger` over the entire region**
(`Scheduler.writeBackColumns`): for every column it adds `(before = task.mass, after = result.mass,
inMat = task.matIx, outMat = result.matIx)`. The ledger sums each tracked species' mass before and after.

- If `ledger.conserved()` (every species balances within `ε · totalCells`) → **commit** (Step 7).
- If not → **atomic HOLD**: nothing is written this cycle, the whole region keeps its previous values, and
  a warning is logged. (This is the region-granularity version of the old advection-batch HOLD.)

Conduction-only cycles skip the mass ledger (they don't move mass); they just clamp temperatures.

---

## Step 7 — Write-back per column (`writeBackColumn`)

For each column (only on a conserved cycle), `MinecraftThermalWorld.writeBackColumn` walks its 24 sections
and, via `ColumnSectionCodec` (the exact inverse of Step 3's index map):

1. **Clamp + persist** — `StepValidator.clean` (non-finite T → snapshot fallback, clamp `[0, 6000] K`) and
   `cleanMass` (clamp `[0, bound]`, NaN/negative → 0); write the section's T and mass into `SectionStore`,
   `demoteIfUniform()` (collapse a now-uniform section back to the cheap form), `put`.
2. **Record the signature** — `recordCellMaterials(outMat)` stores the engine's *output* species as this
   cell's "prior species" for next cycle's seed gate (Step 3).
3. **Settle / dormancy** — `noteSettle(maxΔmass, maxΔT)`: a section whose step barely changed counts down
   toward dormant, so next cycle's Step 2 can drop it from the active set.
4. **Make it visible** — `phaseChanger.applyPhaseChanges` (water↔ice/steam, lava→stone at the second
   boundary) and the **air-aware** `fluidReconciler.reconcile`: it flips the Minecraft *block* to match the
   engine's output species (place water / clear to air), computing only a render level — it **never**
   re-derives mass from block geometry, so it can't fabricate or drop mass.
5. **Wake-on-cross** — if this column moved any mass, wake its loaded neighbour columns
   (`wakeColumnNeighbour`) so they re-enter next cycle to accept incoming flow rather than stranding it at
   the seam. (Column-granular replacement for the old per-section seam wake; over-waking is harmless — the
   neighbour just settles back via `noteSettle`.)

Then the scheduler measures the **server-thread** wall time it spent (snapshot + write-back/reconcile, not
the off-thread engine) and feeds it to the throttle: if it stayed under `COMPUTE_BUDGET_MILLIS = 30 ms` for
`ON_TIME_TICKS_TO_CLIMB` cycles it raises `range` (simulate more), up to `MAX_RANGE`; if it blew the
deadline it holds/lowers. That's how `range` in Step 2 self-tunes.

---

## Step 8 — Loop

The scheduler returns to idle and waits for the next cadence boundary (conduction at 1 Hz, advection at
4 Hz), where the cycle repeats from Step 2 with a freshly-assembled active region. State lives only in
`SectionStore` (truth) + the recorded per-cell signature; the engine is a pure function from one region
snapshot to the next.

---

### Why this shape (the bugs it removes)

- **No internal seams to reconcile in Java.** The whole region is one `World`; vertical flow is within a
  contiguous column and X/Z flow is native via the snapshot, so there's no frozen-halo / antisymmetric
  seam-flux / cross-seam reconciliation — and therefore no `1000 → 2000` doubling.
- **Conservation is enforced twice:** the engine's passes are antisymmetric by construction, and the
  region-wide ledger HOLDs any cycle that still doesn't balance.
- **No mass from nothing:** the single signature-gated seed in `ColumnAssembler` is the only place mass is
  created, and only for genuine player placements; the reconciler only flips blocks, never re-derives mass.
