# Design — Block↔Material rule + Unified Place + Break→Vacuum

**Date:** 2026-06-03 · **Repo:** `/home/claude/ORGE` · branch **`rebuild`**

One spec, one plan, TDD. **Zero engine/.so/gitlink change** (name-match is pure Java; the
native `apply_injections` is already species-agnostic; break is a Java-side stomp + ledger;
the void→vacuum rename is a string). Supersedes the binding-based block→material conversion
and the `movable()`-gated placement special-cases. Builds on `placement-injection-displace`
and `unified-substance-placement`.

## Design law (the user's, restated)

**Engine state is the source of truth. Every cell is ONE substance — an ORGE material id +
mass + temperature. The vanilla block is a lossy *render proxy*, never an identity.** One
vanilla block maps to many ORGE materials (`minecraft:water` → `orge:water` today, `orge:salt_water`
later; `minecraft:air` → `orge:air` | `orge:vacuum` | any future gas). Therefore:

- **Never infer an ORGE material by reading the resulting vanilla block.** Block→material is
  consulted only to seed a *player's PLACE intent*; it is never the authority for ongoing
  simulation state (always engine-recorded) and never for BREAK (always → vacuum, by event).
- `movable()` (finite viscosity) governs **only whether a substance FLOWS after placement** —
  never placement, seed, or displacement semantics. There is no solid/fluid/gas category.

### Cell-state vocabulary (locked)

| term | representation | flow? | heat? |
|---|---|---|---|
| **void** (true "not simulated") | **absent from the engine arrays** — an unloaded cell | n/a | n/a |
| **vacuum** | index-0 sentinel: matIx 0, 0 mass, **finite viscosity** (flow-accepting), conductivity 0 (heat-dead) | **yes** | no |
| **air** | `orge:air`: ~1.2 kg movable gas, real mass + heat | yes | yes |

The index-0 sentinel is currently mis-named `orge:void` in code; it is really **vacuum**. This
spec renames it. "void" survives only as the conceptual name for an unloaded/absent cell.

---

## Part 1 — Block↔Material rule (replaces all bindings)

### 1A. block → material (the one rule)

Pure, data-free, blockstate-blind:

```
materialFor(blockId):
    candidate = orge:<blockId.path>          # namespace dropped
    if the material registry contains candidate → candidate
    else                                      → orge:generic_solid
```

Examples: `minecraft:air → orge:air`, `minecraft:water → orge:water`, `minecraft:stone →
orge:stone`, `minecraft:magma_block → orge:magma_block`, `minecraft:diamond_ore → orge:diamond_ore`
(miss) → `orge:generic_solid`. Modded blocks try `orge:<path>` too. **No tags, no per-block
overrides, no blockstate predicates.**

### 1B. material → block (unchanged)

Stays `representative_block` (default `minecraft:air`). Already implemented in `Material`
(`Material.java:118-120`) and consumed by `MinecraftFluidReconciler`. No change.

> 1A and 1B are **not** inverses. `orge:salt_water.representative_block` may be `minecraft:water`
> while `minecraft:water → orge:water` only. That asymmetry is the whole point of the law.

### 1C. Binding teardown (delete)

Remove every binding mechanism:

- **Data:** `core/src/main/resources/data/orge/orge/bindings/` (the `default.json` file + dir).
- **Java:** `MaterialBindings`, `BlockStatePredicate`, `PropertyView`, `MaterialData.loadBindings`,
  `MaterialBindings.TagMembership`.
- **`ActiveMaterials`:** drop the `bindings` field from `State`, the `bindings()` accessors, and
  the `List<JsonElement> bindings` parameter from `buildState` / `reloadFrom`.
- **`LiveMaterials`:** replace both `materialFor(...)` overloads with the 1A rule (a single
  `materialFor(Block, MaterialRegistry)` / `materialFor(BlockState, …)` that ignores state).
  Remove `LIVE_TAGS`, `propertyValue`, `nameOf`.
- **`Orge.java`:** remove the bindings-load wiring (datapack read + reload hook for bindings).
- **Tests:** delete/trim `MaterialBindingsTest`, `HotSourceBindingsTest`, `ColdSourceBindingsTest`,
  the bindings portions of `MaterialDataTest` / `ActiveMaterialsTest`, and the bindings coupling
  in `DefaultPhaseDataTest`. Add a `LiveMaterials` (or new `BlockMaterialRule`) test: hit,
  miss→generic_solid, `minecraft:air`→`orge:air`.

### 1D. Material set — making the heat-source regression faithful

Under 1A, a material is selected **iff its id equals a vanilla block path**. Pinned heat sources
whose id matches a *blockstate-gated* block would silently become **always-on** (unlit campfire
emitting heat). The user chose **(A) accept the regression** — those become future work — so we
actively remove them so they fall to inert `generic_solid`.

- **DELETE** (blockstate-gated; regress to `generic_solid`):
  `campfire`, `candle`, `copper_bulb`, `redstone_lamp`, `redstone_torch`, `soul_campfire`,
  `lightning_rod`, `furnace_lit`, `powered_redstone`.
- **RENAME** (unconditional emitter whose id ≠ block path; rename so 1A hits it):
  `magma` → `magma_block`, `portal` → `nether_portal`.
- **KEEP** (unconditional, id already matches block — correct to always emit):
  `torch`, `glowstone`, `lantern`, `end_rod`, `redstone_block`, `blue_ice`, `soul_lantern`,
  `fire`, `soul_fire`; plus the non-source materials `water`, `lava`, `ice`, `stone`, `steam`,
  `air`, `generic_solid`.

Net effect: lit/powered heat sources are dormant (future work); always-on emitters and the
lava/water/ice/stone physics are unchanged.

> Known minor regressions accepted: `wall_torch` (id ≠ `torch`) → `generic_solid`; the `*_off`
> variants of redstone gear were never sources anyway. Documented, not fixed here.

---

## Part 2 — Unified PLACE (bugs 2 + 3)

No engine change — `apply_injections` already injects-and-displaces any species.

### 2A. `PlacementInjectionPolicy.isDisplacement` — drop the movable gates

```
isDisplacement(live, incumbent):
    if live == null              → false      # defensive only; every block is a material now
    if incumbent == null         → true       # untracked cell: enqueue for durability (bug-1 rule kept)
    return !live.id.equals(incumbent.id)      # different species → displace-and-inject
```

Removes both `!live.movable()` and `incumbent.movable()`. A **solid** placed over fluid/air/solid
is now captured (fixes **bug 2**: solid-into-fluid deletes it / no push). The reconciler self-write
(`live == incumbent`) still returns false.

### 2B. `ColumnAssembler.java:66` — drop `m.movable()` from the seed gate

```
if (storedMass <= 0f && prior != mat)   # was: m.movable() && storedMass <= 0f && prior != mat
    seeded = m.defaultMass();
```

A fresh solid cell now seeds its `defaultMass` (fixes **bug 3**: ~400-600 kg residual / 0-mass).
For a *captured* placement the existing drain-stomp ordering dedups (assembler seeds → `InjectionDrain`
stomps the cell back to incumbent → engine injection re-places exactly once) — identical to how
fluids behave today, so **no double-seed**. For an *uncaptured* placement (capture bailed: chunk
unloaded, server unbound) the assembler seed is the correct fallback.

### 2C. §9 ledger

A solid injection declares `injected[solidSpecies] += defaultMass` so `SpeciesMassLedger.expect(...)`
keeps `conserved()` true. Already wired via `RegionStepResult` / `expect`; this slice only ensures
solids flow through the same path (no new ledger code expected — verify in TDD).

---

## Part 3 — BREAK → vacuum

Event-driven, no engine change, **no block read**.

### 3A. Explicit break capture

`BlockEvent.BREAK` already carries the **outgoing** state (`Orge.java:198`, arg `state`). Add a
break-capture call distinct from the place/wake path: enqueue a **removal intent** for that cell.
The cell becomes vanilla air, but it is **never read** — the BREAK event is the only signal.

### 3B. Drain → vacuum + sealedLoss

At drain, a removal intent:

1. **Stomps the cell to vacuum** — `matIx[cell] = 0`, `mass[cell] = 0` (index-0 sentinel).
2. **Declares `sealedLoss[incumbentSpecies] += incumbentMass`**, where incumbent species + mass
   come from **engine-recorded state** (`cellMaterials.prior` + `SectionStore` mass), not the block.

The engine then steps a vacuum cell; neighbours flow in via normal advection. §9 does not HOLD
because the removed mass is declared as a sealed loss. **Mass → item-entity is explicitly deferred.**

### 3C. Representation

Model the removal as a `PendingInjections` variant flagged as a **removal** (e.g. a boolean
`removal` on `Intent`, or a separate queue) — **not** as an injection of species `orge:vacuum`.
Reason: `InjectionDrain` treats a resolved species index of `0` as "unresolvable → skip the intent"
(`InjectionDrain.java:75`), and `orge:vacuum` IS index 0, so reusing the injection path would
silently swallow every break. The drain must branch on the removal flag: clear the cell to index-0
(matIx 0, mass 0) and ledger the recorded incumbent as `sealedLoss`, bypassing the `species==0` skip.

Capture must distinguish PLACE (→ `PlacementCapture` / injection) from BREAK (→ removal intent)
**by event**, because the post-break block (air) is indistinguishable from a genuine air placement
and reading it would violate the design law.

---

## Part 4 — Terminology rename (cosmetic, aligns with the law)

Rename the index-0 sentinel `orge:void` → **`orge:vacuum`** (`MaterialLut.VOID` → `MaterialLut.VACUUM`,
the `Identifier` path, and the doc comments in `MaterialLut`, `MaterialChangeReseed`, `StepValidator`,
`ColumnAssembler`, `MinecraftThermalWorld`). Behaviour is byte-identical (same molar 0 / mass 0 /
finite-viscosity / k=0 sentinel) — only the name changes. "void" is retired as a cell-state name.

---

## Non-goals (explicit)

- Latent heat, temperature-dependent material curves (still out per §6).
- Re-adding blockstate-gated heat sources by a non-binding mechanism (future work).
- Salt-water / multi-material-per-block disambiguation via ORGE items (future work).
- Mass → item-entity on break (deferred).
- Any engine / `liborge.so` / gitlink change.

## Gates

- `JAVA_HOME=/home/claude/jdk21 ./gradlew :core:test` (fast) — green.
- `:core:integrationTest` (real `.so`) — green; placement + injection ITs unaffected by Java-only changes.
- `./gradlew build` (both loaders).
- Push `rebuild` after every commit (user tests from origin/rebuild).
- `MaterialThreeMassJsonTest` may fail from the user's LOCAL `water.json`/`lava.json` edits — that is
  their working tree, not the repo; do **not** change those tests without new canonical values.

## Open items resolved during design (record)

- **break = vacuum, NOT void** — corrects the earlier `unified-substance-placement` wording
  ("BREAK = void (0kg,0K)"). Void = unloaded/absent; vacuum = index-0 flow-accepting empty cell.
- **block→material = name-match, not bindings** — supersedes DESIGN.md §6's "tag bindings +
  overrides + fallback" registration paragraph (lines ~150-159); DESIGN.md to be updated to match.
- **heat-source regression = accepted (option A)** — DELETE/RENAME buckets in 1D realize it.
