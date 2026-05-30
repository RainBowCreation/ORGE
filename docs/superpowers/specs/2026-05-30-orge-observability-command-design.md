# Spec: `/orge` observability command (engine-audit track — Topic A)

**Date:** 2026-05-30 · **Branch:** `rebuild` · **Status:** design (approved in brainstorm; pre-plan)

Seeded by `docs/superpowers/notes/2026-05-30-engine-audit-observability-brief.md` (Topic A).
This is the **first** of the engine-audit track. Scope decision from the brainstorm: build
**A alone first**, then a combined **B+C** spec (ambient + heat sources) since those two share a
new material temperature field. A is cleanly independent and unlocks manual engine auditing with
zero coupling to B/C.

---

## 1. Goal

Make the thermal core **observable and drivable** from in-game so the conduction/phase math can be
audited: read a cell's temperature/mass, and inject known temperatures to watch them evolve.

> **Audit it enables:** `set` a hot cell, watch it diffuse per-second via `get`, and confirm the
> rate matches the finite-difference kernel; or set up water next to a (manually pinned) hot cell and
> watch it cross 373.15 K toward `orge:steam`.

A pure **server command** is the audit-critical surface: cheapest, most precise, and fully
**headless-unit-testable** (there is no `runClient` in the sandbox). No client/render code in v1.

### Out of scope (v1)

- Thermometer item, debug HUD, heatmap/tint overlay (the brief's other observability candidates).
- Topic B (ambient/init) and Topic C (heat sources) — their own B+C spec.
- The **client-cache read source** and any **network transport** for it — deferred with the §3
  client-distribution track. v1 ships only the seam that lets it drop in later (see §5).

---

## 2. Command surface

```
/orge get <pos>                       read one cell
/orge section <pos>                   summarize the 16³ section containing <pos>
/orge set  <pos> <K> [massKg]         write one cell                 (op, level 2)
/orge fill <from> <to> <K> [massKg]   write a box of cells           (op, level 2)
```

- **Positions** are vanilla `BlockPos` arguments (`~` relative coords work for free).
- **Dimension** is taken from the command source's level.
- **Temperatures** echoed in K with °C in parens for sanity; **mass** in kg. Mass is **read and
  writable** (`set`/`fill` take an optional `massKg`); when omitted, mass is left unchanged.
- Example feedback:
  - `get` → `cell (12,71,-4) [minecraft:overworld]: 372.40 K (99.25 °C), 1000.0 kg, form=FULL`
  - `section` → `section (0,4,-1): form=FULL dirty=true | T 285.0/310.2/1400.0 K (min/avg/max) | mass 0.0/812.5/1000.0 kg | 88/4096 cells differ from ambient`
  - `set` → `set (12,71,-4) → 1400.00 K (mass unchanged)`
  - `fill` → `filled 27 cells in [(0,70,0)..(2,72,2)] → 1400.00 K`

### Access model

| Op | Who | Rule |
|----|-----|------|
| `set`, `fill` | **op only (level 2)** | Brigadier `requires(s -> s.hasPermission(2))` **and** re-checked in logic (belt-and-suspenders). Server-authoritative writes. |
| `get`, `section` | **op (≥2)** | unrestricted — any cell, any distance. |
| `get`, `section` | **non-op** | allowed iff the **target section is within the configured section read-range `R` of the source's section**, using the scheduler's own sphere test `dx²+dy²+dz² ≤ (R-1)²` (in sections). I.e. "you can read any cell your sim sphere could include." |

- **Non-op out of range** → `out of range; you can read cells within R sections of you (op to read anywhere)`.
- **Non-op source with no position** (console / command block) → deny the read (proximity can't be verified).
- **`R` source:** the server's configured section range. v1 defaults **`R = Scheduler.MAX_RANGE` (4
  sections ≈ the widest sim sphere)** so the readable area is *stable* (it does not shrink when a
  worker throttles its dynamic range). Bound behind a `ReadRangeProvider` seam (§3) so a future
  server-config value — and the later client-side range calc — read through the same source and never
  drift from the command's notion of "nearby."

### Error handling (explicit — no silent loss)

- Dimension has no `SectionStore` → `no ORGE data for dimension <id>`.
- `set`/`fill` target column **not loaded** → fail with `target section not loaded; move closer`
  (never write into a column that has unloaded since, which would be dropped).
- Read of a **never-simulated** section → returns the ambient baseline, annotated
  `form=UNIFORM(ambient)` so the auditor knows it is implicit, not evolved.
- **Y outside** the level's build height → rejected with the valid range.
- **`fill` box too large** → rejected. Cap at **32³ = 32 768 cells** to keep the single server-thread
  write bounded (the command runs on the server thread, same as the store).

---

## 3. Architecture

Follows the established **pure-core-behind-a-Minecraft-seam** convention already used across the
engine (pure `PhasePlanner` behind the `PhaseChanger` seam + `MinecraftPhaseChanger`; pure
`Scheduler` behind `ThermalWorld`; `SectionStore` behind the platform seam). All behavior lives in
pure, headless-tested classes; the Minecraft/Brigadier adapter is thin.

```
                     Brigadier tree (CommandRegistrationEvent)
                                  │  parse args, permission predicates
                                  ▼
   OrgeCommands  ──builds Request──►  OrgeCommandLogic (PURE)
   (MC adapter)  ◄──Response lines──         │
                                  proximity gate → read-source chain / write sink
                                             │
                 ┌───────────────────────────┼────────────────────────────┐
                 ▼                            ▼                            ▼
       List<ThermalReadSource>        ThermalWriteSink            ReadRangeProvider
       (client cache → server)        (server store only)         (→ Scheduler.MAX_RANGE)
                 │                            │
                 ▼                            ▼
       ServerStoreReadSource          ServerStoreWriteSink
                 └──────────► SECTION_STORES (SectionStore) ◄──────────┘
```

### Units

1. **`CellAddress` (pure).** `BlockPos → SubchunkKey(x>>4, y>>4, z>>4)` + `cellIndex =
   (x&15) | ((y&15)<<4) | ((z&15)<<8)`. The exact inverse of `LiveMaterials.blockAt` (x-fastest,
   `x + 16y + 256z`), so the command addresses the same cell the engine indexes. (`>>4` on an `int`
   equals `Math.floorDiv(_,16)`, correct for negative coordinates.)

2. **`ThermalReadSource` (seam, chainable).** `Optional<SectionView> section(Identifier dim,
   SubchunkKey key)` — *absent* = "I don't have it, try the next source." `OrgeCommandLogic` holds an
   **ordered `List<ThermalReadSource>`** and uses the first hit.
   - **v1 chain:** `[ ServerStoreReadSource ]` (over `SECTION_STORES`).
   - **Future:** prepend `ClientCacheReadSource` → freshest-client-data-first, server fallback.
   - `SectionView` = immutable read snapshot: `float tempAt(int)`, `float massAt(int)`,
     `SectionData.Form form()`, and an `ambient` flag (true when the section is the materialized
     never-simulated baseline). Built from a `SectionData` by the server source.

3. **`ThermalWriteSink` (seam).** Server-authoritative writes only:
   - `boolean isLoaded(Identifier dim, SubchunkKey key)`
   - `void writeTemp(Identifier dim, SubchunkKey key, int cell, float k)`
   - `void writeMass(Identifier dim, SubchunkKey key, int cell, float kg)`
   Live `ServerStoreWriteSink`: `store.get(key)` → `setTemperature/setMass(cell, v)` →
   `store.put(key, data)` (which marks the column dirty). The next `Scheduler.snapshot()` picks the
   write up naturally — both run on the server thread, so no locking and no race.

4. **`ReadRangeProvider` (seam).** `int sectionReadRange()` → v1 returns `Scheduler.MAX_RANGE`. A
   future server-config / client-side calc supplies the real value through this one seam.

5. **`OrgeCommandLogic` (pure — the analog of `PhasePlanner`).** Input: a `Request` record
   (operation; dimension; primary `BlockPos` (+ second for `fill`); optional `K`, optional `massKg`;
   **`sourceSection`**; **`permissionLevel`**; level min/max build height). Holds the read-source
   list, the write sink, and the `ReadRangeProvider`. Steps:
   1. **Validate** Y in `[minY, maxY)`; for `fill`, box cell-count ≤ 32 768.
   2. **Permission/proximity gate** (write → op; non-op read → in-sphere via the shared sphere test).
   3. **Resolve** — reads walk the source chain; writes go to the sink (after `isLoaded`).
   4. **Format** a `Response` (ordered plain feedback lines + ok/fail). No Brigadier, no
      `Component`, no Minecraft text — strings only, so it is fully unit-testable.

6. **`OrgeCommands` (Minecraft adapter — thin, like `MinecraftPhaseChanger`).** Builds the Brigadier
   tree under `CommandRegistrationEvent`: arg types (`BlockPosArgument`, `FloatArgumentType`),
   `requires` predicates on the `set`/`fill` nodes, parse → `Request` (dimension + `sourceSection`
   from `CommandSourceStack`; `permissionLevel` from the source), call `OrgeCommandLogic`, map each
   `Response` line to chat feedback. Registered once from `Orge.init`. As little logic as possible
   lives here so the headless suite covers nearly everything.

### Shared-code change (targeted improvement to code we touch)

- Add **`SphereUnion.contains(SubchunkKey anchor, SubchunkKey target, int range)`** (pure) and
  refactor the existing `SphereUnion.expand(...)` to call it. One definition of sphere membership,
  now shared by the **scheduler's batch** and the **command's proximity gate** — they cannot drift.

### Wiring (`Orge.init`)

Alongside the existing `LifecycleEvent` / `TickEvent` registrations, add a single
`CommandRegistrationEvent.EVENT.register(...)` that constructs `OrgeCommands` with:
- read sources `[ new ServerStoreReadSource(SECTION_STORES) ]`,
- write sink `new ServerStoreWriteSink(SECTION_STORES)`,
- `ReadRangeProvider` → `Scheduler.MAX_RANGE`.

One common Architectury event ⇒ one registration covers Fabric **and** NeoForge (verified:
`dev.architectury.event.events.common.CommandRegistrationEvent` is present in architectury 19.0.1).

---

## 4. Data flow

**Read (`get` / `section`):**
`Brigadier parse → Request(source pos→section, permLevel) → logic: validate Y → proximity gate
(op? else target-section ∈ sphere(sourceSection, R)) → walk read-source chain (client cache →
server store) → first SectionView → format → feedback`.

**Write (`set` / `fill`):**
`Brigadier parse (requires op) → Request → logic: validate (Y, fill cap) → re-check op → for each
target cell: isLoaded? → writeTemp/writeMass → store marks column dirty → format count → feedback`.
The mutated `SectionData` is the same instance the next `snapshot()` clones, so the injected temps
enter the very next conduction step.

---

## 5. Forward-compatibility notes (for the §3 client-distribution track)

- **Read-source chain:** `ThermalReadSource` is the only seam the future `ClientCacheReadSource`
  needs. Its transport (a client-side command via `ClientCommandRegistrationEvent`, or a
  server→client query) is **deferred** — v1 implements only the server fallback, which is also the
  permanent last link.
- **`ReadRangeProvider`:** the command's "nearby" radius and the client worker's sim sphere both read
  through this one provider, so they stay consistent when real server-config range lands.
- **`SphereUnion.contains`:** the membership math is now shared, so any future change to sphere shape
  updates the scheduler and the command together.

---

## 6. Testing

Built **TDD via subagent-driven-development**, commit-per-task on `rebuild`, push `origin/rebuild`,
no PR/merge while v1 is incomplete — the same loop as the §7 phase-change track.

- **`CellAddressTest`** — `BlockPos → (SubchunkKey, cell)` across negative coords and section
  boundaries; round-trips against `LiveMaterials.blockAt` (decode(encode) == identity).
- **`SphereUnionTest`** — new `contains(anchor, target, range)`: in/out/boundary at exactly
  `(R-1)²`; and that `expand(...)` is unchanged after the refactor.
- **`OrgeCommandLogicTest`** (the bulk, fully headless, with fake `ThermalReadSource`s / `ThermalWriteSink`):
  - `get` reads ambient baseline (annotated `UNIFORM(ambient)`); `set` then `get` reads back the value.
  - `section` stats (form, dirty, min/avg/max T and mass, differ-from-ambient count).
  - `fill` writes a box and reports the exact count; mass-omitted leaves mass unchanged.
  - **Read-source ordering:** a 2-source list proves client-cache-first / server-fallback (first hit wins).
  - **Proximity:** op reads anywhere; non-op in-sphere ok; non-op out-of-sphere denied; non-op
    no-position denied; boundary exactly at `(R-1)²`.
  - **Errors:** no store for dimension; write to not-loaded column; Y out of build height; `fill`
    over the 32³ cap.
- **`OrgeCommands`** adapter stays thin; like `MinecraftPhaseChanger` it is validated by
  manual/integration testing (no `runClient` in the sandbox), with minimal logic of its own.

---

## 7. References

- Brief: `docs/superpowers/notes/2026-05-30-engine-audit-observability-brief.md`.
- DESIGN.md §4 (range = sphere of sections), §5 (per-cell metadata / store), §8 (scheduler).
- Conventions/build env: `docs/superpowers/plans/2026-05-30-phase-change-track.md`.
- Code touched/seamed: `Scheduler`, `SphereUnion`, `SectionStore`/`SectionData`, `LiveMaterials`,
  `Orge.init`. Memories: `orge-scheduler`, `orge-section-store`, `orge-phase-change`.
