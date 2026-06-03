# Design — Durable per-cell Material + first-touch block rule + unified place/break→vacuum

**Date:** 2026-06-03 · **Repo:** `/home/claude/ORGE` · branch **`rebuild`**

One spec, one plan, TDD. Builds on `placement-injection-displace`, `unified-substance-placement`,
and the §5 section store. **Engine/.so/gitlink:** no change expected (all Java); confirm in TDD.

## Design law (the user's, the spine of this spec)

**Engine state — not the vanilla block — is the source of truth for what a cell IS.** Every cell is
ONE substance: an ORGE **material id** + mass + temperature. The vanilla block is a lossy *render
proxy*: one block maps to many materials (`minecraft:water` → `orge:water` | `orge:salt_water`;
`minecraft:air` → `orge:air` | `orge:vacuum` | any future gas).

Therefore the per-cell **material id is stored durably server-side, exactly like temperature and
mass already are.** The vanilla block → ORGE material lookup is consulted **only for a cell we have
never seen** — freshly generated terrain (first touch) or a player's explicit PLACE — never to
re-derive identity for an already-stored cell, and never for BREAK.

`movable()` (finite viscosity) governs **only whether a substance FLOWS after placement** — never
placement, seed, or displacement. No solid/fluid/gas category exists.

### Cell-state vocabulary (locked)

| term | representation | flow? | heat? |
|---|---|---|---|
| **void** (true "not simulated") | **absent from the engine arrays** — an unloaded cell | n/a | n/a |
| **vacuum** | index-0 sentinel: matIx 0, 0 mass, **finite viscosity** (flow-accepting), conductivity 0 (heat-dead) | **yes** | no |
| **air** | `orge:air`: ~1.2 kg movable gas, real mass + heat | yes | yes |

The index-0 sentinel is currently mis-named `orge:void`; it is really **vacuum**. Renamed in Part 5.

---

## Part 0 — Durable per-cell material store (the keystone) — §5 extension

Today `SectionData` holds two `float[4096]` (temperature, mass) in UNIFORM/FULL forms; material is
NOT stored (`CellMaterialTracker` keeps only an in-memory last-cycle signature for change detection).
This part adds material identity as a **third persisted per-cell quantity**.

### 0A. `SectionData` gains a material layer
- A per-section **palette** `List<Identifier>` + a per-cell index array (`char[4096]`), mirroring how
  Minecraft stores blockstates. Few distinct materials per section ⇒ the palette stays tiny and the
  index array compresses well (same RLE/compression the `mass` array already uses).
- Forms extend naturally: a UNIFORM section carries a **single palette entry** (one material for all
  4096 cells, e.g. an all-stone or all-air section); promotion to FULL back-fills the index array.
- `materialAt(int cell) -> Identifier`. A never-simulated/ambient section has **no material layer**
  (see 0C) — `materialAt` is only valid once the section is materialized.

### 0B. Serialization (`SectionCodec` / region files) — version bump + back-compat
- Bump the section record version. New format appends `palette` (count + ids) and the packed index
  array after the existing temp/mass payload.
- **Back-compat:** a section written by the OLD format has no material layer. On load it is flagged
  `materialUnknown`; the assembler reconstructs identity from the block via the first-touch rule
  (Part 1) and the next write-back persists it forward. No save migration tool; old saves self-heal
  on first simulation.

### 0C. Sparsity — do NOT bloat saves
The untouched world stays UNIFORM and stores **no per-cell material** — only sections the scheduler
actually simulates (the player-sphere active+apron) materialize a material layer. This matches the
existing on-demand materialization of temp/mass; saves grow only where ORGE has run.

### 0D. Authority + the `CellMaterialTracker` retirement
`SectionStore` becomes the per-cell **material authority** (`materialAt`). The in-memory
`CellMaterialTracker` and the block-diff `MaterialChangeReseed` exist *because* identity was
block-derived; with durable identity, a "material change" is a player EVENT recorded at the event
(Parts 3-4), not inferred by diffing the block each cycle. Retire `CellMaterialTracker` +
`MaterialChangeReseed` (or reduce them to the engine-output recorder the write-back still needs).
Validate the exact reduction in TDD — do not delete blind.

---

## Part 1 — block → material is the FIRST-TOUCH rule only

```
firstTouchMaterial(blockId):           # used ONLY for a cell with no stored material, or a PLACE event
    candidate = orge:<blockId.path>    # namespace dropped
    if registry contains candidate → candidate
    else                           → orge:generic_solid
```

`minecraft:air → orge:air`, `minecraft:water → orge:water`, `minecraft:magma_block → orge:magma_block`,
`minecraft:diamond_ore` (miss) → `orge:generic_solid`. No tags, no overrides, no blockstate. This is
the **only** block→identity touchpoint, and only for unknown/first-touch/placed cells.

**material → block (render)** is unchanged: `representative_block` (default `minecraft:air`), consumed
by `MinecraftFluidReconciler`. 1A and render are not inverses (`orge:salt_water.representative_block`
= `minecraft:water` while `minecraft:water` first-touches to `orge:water`).

---

## Part 2 — Assembler reads STORED material (not the block)

`ColumnAssembler` (and the section source feeding it) build each cell's `matIx` from:
- **stored material** if the section has a material layer (Part 0) — authoritative; the block is
  never consulted for identity;
- else **first-touch** `firstTouchMaterial(block)` (never-simulated cell), which also seeds the
  cell's material layer on the next write-back.

The mass-seed gate keeps its purpose (seed `defaultMass` for a fresh cell) but is driven by the
stored material, not a block re-read. With identity durable, vacuum persists (a vacuum cell stays
`matIx 0` across cycles even though its block is air) and `orge:salt_water` will persist for free.

---

## Part 3 — Unified PLACE (bugs 2 + 3)

On a player PLACE event (the only block→identity entry besides first-touch):
- Record the cell's **stored material** = `firstTouchMaterial(placedBlock)`.
- Enqueue a displace-and-inject intent. `PlacementInjectionPolicy.isDisplacement` **drops the
  `live.movable()` / `incumbent.movable()` gates**: capture iff `incumbent == null` OR
  `live.id != incumbent.id` (reconciler self-write `live == incumbent` still skips). A **solid**
  placed over fluid/air/solid is now captured (fixes **bug 2**: no push / fluid deleted).
- `ColumnAssembler` seed gate drops `m.movable()` so a fresh **solid** seeds its `defaultMass`
  (fixes **bug 3**: ~400-600 kg residual). The drain-stomp ordering dedups the captured path exactly
  as it does for fluids today — no double-seed; the assembler seed is the fallback for an uncaptured
  placement (capture bailed: chunk unloaded / server unbound).
- Engine `apply_injections` is already species-agnostic → it displaces the incumbent for solids too.
- §9: a solid injection declares `injected[species] += defaultMass` via the existing
  `RegionStepResult` / `ledger.expect(...)` path (verify, no new ledger code expected).

---

## Part 4 — BREAK → vacuum (durable)

On a player BREAK event (`BlockEvent.BREAK` carries the **outgoing** state — never read the resulting
air block; the event is the signal):
- Record the cell's **stored material = `orge:vacuum`** (index 0). Because identity is now durable
  (Part 0), the cell stays vacuum across cycles — the assembler will not re-seed `orge:air`, so no
  1.2 kg air is fabricated.
- Enqueue a **removal intent** (a `PendingInjections` variant flagged `removal`, NOT an injection of
  species `orge:vacuum`: `InjectionDrain.java:75` treats a resolved index of `0` as "unresolvable →
  skip", and vacuum IS index 0, so the injection path would silently swallow every break). The drain
  branches on the flag: clear the assembled cell to `matIx 0 / mass 0`.
- The engine then steps a vacuum cell; neighbours flow in via normal advection. The removed mass left
  the sim at the assembly boundary (the cell entered the step as vacuum), so the §9 step-gate sees a
  conserved step — **no `sealedLoss` declaration is needed** (the spec's earlier `sealedLoss`
  requirement was an artifact of the pre-durable model; verify conservation holds in TDD and only add
  a ledger term if a test demands it). **Mass → item-entity is explicitly deferred.**

---

## Part 5 — Bindings teardown + material set

### 5A. Delete every binding mechanism
`data/orge/orge/bindings/` · `MaterialBindings` · `BlockStatePredicate` · `PropertyView` ·
`MaterialData.loadBindings` · `MaterialBindings.TagMembership` · `ActiveMaterials.State.bindings` +
`bindings()` + the `List<JsonElement> bindings` args of `buildState`/`reloadFrom` ·
`MaterialJsonLoader.readBindings`/`BINDINGS` · both `LiveMaterials.materialFor` overloads (replaced by
the Part-1 rule) + `LIVE_TAGS`/`propertyValue`/`nameOf` · tests `MaterialBindingsTest`,
`HotSourceBindingsTest`, `ColdSourceBindingsTest`, the bindings parts of `MaterialDataTest` /
`ActiveMaterialsTest`, the bindings coupling in `DefaultPhaseDataTest`.

### 5B. Material set — making the heat-source regression faithful (option A, chosen)
Under Part 1, a material is first-touched iff its id equals a vanilla block path. Pinned heat sources
whose id matches a *blockstate-gated* block would silently become always-on. Option A = accept the
regression, so we remove them so they fall to inert `generic_solid`:
- **DELETE** (blockstate-gated): `campfire`, `candle`, `copper_bulb`, `redstone_lamp`,
  `redstone_torch`, `soul_campfire`, `lightning_rod`, `furnace_lit`, `powered_redstone`.
- **RENAME** (unconditional emitter, id ≠ block path): `magma` → `magma_block`, `portal` → `nether_portal`.
- **KEEP** (unconditional, id matches block): `torch`, `glowstone`, `lantern`, `end_rod`,
  `redstone_block`, `blue_ice`, `soul_lantern`, `fire`, `soul_fire`; plus non-sources `water`, `lava`,
  `ice`, `stone`, `steam`, `air`, `generic_solid`.

Accepted minor regressions: `wall_torch` (id ≠ `torch`) → `generic_solid`; lit/powered sources become
future work.

---

## Part 6 — Rename `orge:void` → `orge:vacuum`

`MaterialLut.VOID` → `MaterialLut.VACUUM`, the `Identifier` path, and the doc comments in `MaterialLut`,
`MaterialChangeReseed` (if retained), `StepValidator`, `ColumnAssembler`, `MinecraftThermalWorld`.
Byte-identical behaviour (same molar 0 / mass 0 / finite-viscosity / k=0 sentinel) — only the name.
"void" is retired as a cell-state name.

---

## Open design decisions (please confirm at review)

1. **Material storage = per-section palette + `char[4096]` index array**, UNIFORM = single palette
   entry (mirrors temp/mass). Alternative: a raw global-LUT index per cell (rejected — not
   self-contained across LUT rebuilds / reloads).
2. **Region version bump with self-healing back-compat** (old sections → `materialUnknown` →
   first-touch reconstruct → persisted forward). No offline migration tool.
3. **Retire `CellMaterialTracker` + `MaterialChangeReseed`** (their job — detect block-vs-stored
   divergence — is obsolete once identity is durable and changes arrive as events). Reduce to the
   engine-output recorder the write-back needs, validated in TDD.
4. **Sparsity:** only scheduler-simulated sections store a material layer; the untouched world stays
   UNIFORM and reconstructs from blocks on first touch (saves grow only where ORGE runs).

## Non-goals
Latent heat / temp-dependent curves; re-adding blockstate heat sources by a non-binding mechanism;
salt-water / multi-material-per-block disambiguation via ORGE items; mass → item-entity on break;
any engine / `liborge.so` / gitlink change.

## Gates
`JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` · `:core:integrationTest` (real `.so`) ·
`./gradlew build` (both loaders). Push `rebuild` after every commit. `MaterialThreeMassJsonTest` may
fail from the user's LOCAL `water.json`/`lava.json` edits — their working tree, not the repo; do not
change those tests without new canonical values.

## DESIGN.md follow-ups (do in the plan)
- §6 "Registration API" loses the tag-bindings/overrides paragraph; gains the first-touch name-match
  rule + the durable per-cell material store.
- §5 gains the per-cell material field alongside temperature + mass.
