> **SUPERSEDED re: material LUT** — see `docs/superpowers/specs/2026-06-03-engine-resident-material-table-design.md`.
> `matIx` ids are now globally STABLE (fixed per material at load/`/reload`, slot 0 = VACUUM, slots 1..N
> = `MaterialRegistry.all()` sorted by namespaced id). The LUT is engine-resident (register-once via
> `orgeRegisterMaterials`), NOT shipped per `orgeStepWorld` call. Passages below describing a per-step /
> batch-local / first-seen LUT are historical.

# ORGE v2 — Design

Overhauled Realistic General Elements. A base mod that gives **every block (including
air and liquids) a temperature and a mass**, simulates heat conduction with a native
C++ engine (ORGE-ENGINE), and changes blocks via phase transitions. v1 ships the thermal
core; fluid dynamics ("water physics, no source blocks") follow in Phase 2.

This document is the authoritative record of the design decisions made during the
rebuild grilling session. `/old/` holds the previous Fabric-1.20.1 implementation as
reference only.

---

## 1. Platform & module layout

- **Architectury multiloader**, Minecraft **1.21.x**, **Java 21**.
- Modules:
  - `core` — loader-agnostic, no Minecraft imports where avoidable: material model,
    scheduler, wire protocol, FFI bindings to the engine, the region-store format,
    and all pure logic. Unit-testable in isolation.
  - `fabric-1.21` / `neoforge-1.21` — platform glue via Architectury `ExpectPlatform`.
    Each module is internally split into `client/` and `server/` packages.
- `ORGE-ENGINE` — git submodule at repo root (the C++ thermal engine).

> Naming intent: `core` + `<loader>-<version>` modules, realised on top of
> Architectury's common/fabric/neoforge mechanism.

## 2. The engine (ORGE-ENGINE)

Today the engine is a **pure finite-difference heat-conduction solver** that owns its
own `World` and runs its own 1 Hz loop (`SimServer`), talking over a JSON-line socket,
with an SDL3 renderer. Its `Material` is `{heatCapacity, thermalConductivity,
defaultMass, molarMass}`. It has **no** phase change, viscosity, or fluid flow.

For v2 it is repurposed:

- **Stateless per-task stepper.** Strip the self-running `SimServer` loop, the SDL
  renderer, and the socket server. Expose a pure `step()` entry point: given a batch of
  subchunks + a 1-cell neighbor halo (temps + material indices) + a `lutEpoch` selecting
  the **engine-resident** material table + dt, run **one** step and return new temperatures
  (and, in Phase 2, mass). The material LUT is registered once per load/`/reload`, not
  shipped per step (see the SUPERSEDED banner above).
- **In-process via JNI** (`System.load`, stable on Java 21 — *not* Panama:
  `java.lang.foreign` is a preview API on Java 21 that requires `--enable-preview`
  at compile and runtime, so a vanilla Minecraft launcher cannot load it; see
  `docs/superpowers/specs/2026-05-29-engine-track-ffi-design.md`). JNI also accesses
  the Java arrays zero-copy via `GetPrimitiveArrayCritical`. No child process, no
  socket, no per-tick serialization across a process boundary.
- **Distribution:** ORGE-ENGINE's CI builds `liborge` for
  `{windows, linux, macos} × {x64, arm64}` and publishes them as release artifacts.
  The mod's Gradle pulls the pinned version and packs all platform libraries into the
  jar; at runtime `NativeLoader` extracts + `System.load`s the matching library
  (no jextract / generated bindings; see Build & native integration notes below).
- The engine keeps its per-section / per-chunk timing (`section_ms_last`,
  `chunk_ms_last`); that is the signal the scheduler uses for health throttling.

## 3. Topology — server-orchestrated worker pool

Not literal peer-to-peer (Minecraft clients cannot address each other). The server is
the hub; **clients are compute workers**; all results flow hub-and-spoke.

- **The mod is required** on every client → every connected client is a worker.
- The **server is a scheduler + authoritative save-state store** with ~0 per-tick
  physics cost.
- The server keeps **one idle fallback engine instance**, used *only* for subchunks no
  healthy client can cover (e.g. force-loaded regions with no player). Idle under normal
  multiplayer load, so the server stays cheap.
- **Single-player** uses the integrated server's local client as the sole worker.

Rationale: the previous design ran the engine on the server for every player's region,
which melted the server CPU as players spread across regions. Distributing the
conduction FLOPs to clients is the entire point of the rebuild.

## 4. Simulation unit & cadence

- A **subchunk = one 16×16×16 section**, addressed by `(cx, sectionY, cz)` — matches
  both Minecraft's `ChunkSection` and the engine's section granularity.
- **Range N = a 3D sphere of sections** measured from the player's current section:
  range 1 = the player's section only; range 2 = all sections within radius 2 in every
  direction (incl. up/down); etc. Task count ≈ (4/3)πr³.
- The simulation steps **once per real second** (dt = 1.0 s, every 20 ticks).

## 5. Per-cell metadata & persistence

- Each cell stores **three durable per-cell quantities: `temperature` (K), `mass` (kg),
  and the ORGE material id** — the engine cell, not the vanilla block, is the source of
  truth for what a cell *is*. The block→material map is consulted only on **first touch**
  (a never-stored cell) — see §6. Stored identity lets `orge:vacuum` (a broken cell) and
  `orge:salt_water` persist even though their `representative_block` is shared (air / water).
- In memory: two parallel `float[4096]` arrays (T, mass) per loaded section, plus a
  **material layer** — a per-section palette `List<Identifier>` (slot 0 = `orge:vacuum`)
  + a `char[4096]` index array, allocated lazily on first material write (mirrors how
  Minecraft stores blockstates; the palette stays tiny and the index array compresses well).
- On disk: a **separate compressed region store** under `world/orge/` (e.g.
  `r.<x>.<z>.orge`), loaded/unloaded alongside the chunk. The vanilla `.mca` files are
  never touched.
- Each section is serialized as either:
  - **UNIFORM** — one temperature + one mass (the common case for sections far from any
    heat source), or
  - **FULL** — `deflate/zstd(T[4096])` + `deflate/zstd(mass[4096])`, once a gradient
    forms,
  followed by an optional **material block** (`hasMaterials` flag; if set, the palette
  ids + the deflated `char[4096]` index array). The codec is **version 2**; legacy **v1**
  blobs (no material block) load as material-unknown and self-heal — the assembler
  reconstructs identity from the block via first-touch and the next write-back persists it
  forward (no migration tool). **Sparsity:** only sections the scheduler actually simulates
  materialize a material layer; the untouched world stays UNIFORM and reconstructs on first
  touch, so saves grow only where ORGE has run.
- A never-simulated section is implicitly `UNIFORM(biome-ambient T, material default_mass)`
  and costs ~nothing. Ambient T is derived from biome temperature at generation, with a
  fixed fallback (~285 K).
- `mass` is treated as **fluid level** from day one (1000 kg ≈ a full 1 m³ water block),
  so Phase-2 fluid dynamics need no storage rework.

## 6. Material model

Flat **constants** per material — properties do not vary with temperature (by design,
not a v1 limitation). The canonical schema below is authoritative: the JSON
(`data/<ns>/orge/materials/<id>.json`), the `Material` record, the loader, the engine LUT,
and the phase system all conform to it — **no more, no fewer fields** (full spec:
`docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md`).

**REQUIRED** (the loader errors if any is missing — clean data is mandatory):

| field | who uses it | meaning |
|---|---|---|
| `thermal_conductivity` | engine (conduction) | W/(m·K) |
| `heat_capacity` | engine (conduction) | J/(kg·K) |
| `molar_mass` | engine (sort) | gravitational sort key — **higher sinks** |
| `default_mass` | Java (seed) | kg placed in a cell when this material is first created |
| `default_temperature` | Java (seed) | natural/seed temperature (K) |

**OPTIONAL** (each defaults when absent — keeps JSON minimal):

| field | absent ⇒ | meaning |
|---|---|---|
| `viscosity` | **frozen** (`+∞`) | flow resistance: `0` = fastest, higher = slower ooze. **Omit for a static solid.** Movable ⟺ viscosity finite. |
| `min_mass` | `= default_mass` | relaxed per-cell mass; a free body expands until cells near this |
| `max_mass` | `= default_mass` | per-cell compression ceiling; a cell never exceeds this |
| `min_temp` / `max_temp` | no phase change | below `min_temp` → `min_target`; above `max_temp` → `max_target` |
| `min_target` / `max_target` | — | **material id** (not block id) to become; required if its temp is set |
| `representative_block` | `minecraft:air` | block **drawn** for this material — *not* its identity (see below) |
| `pinned` | `false` | hold cell temperature at `default_temperature` every tick (Dirichlet heat source/sink) |

- **Identity is the material id, not the block.** `representative_block` is only what's
  *drawn*; multiple materials may share one block (an invisible gas is just a material with
  `representative_block: minecraft:air` but its own `molar_mass`/`viscosity`). Phase change
  is **material → material**; rendering is a separate **material → block** lookup.
- **No `state` field, no `min_flow_mass`** — `viscosity` covers movability, `representative_block`
  covers rendering, `min_mass` replaces `min_flow_mass`.

- **Heat transfer is constant-property forward-Euler conduction**, read directly from
  these constants each step (no curve lookup in the hot loop):
  - Per face, the effective conductivity is the **harmonic mean** of the two cells'
    `thermal_conductivity` (`keff = 2·k₁·k₂/(k₁+k₂)`, and `0` if either is ≤ 0 — that is
    how inert/void cells block heat).
  - Each cell accumulates `dT += keff · (T_neighbor − T_cell) · inv_dx²` over its 6 faces.
  - Thermal capacity is `Cth = mass_kg · heat_capacity` (current cell mass, not
    `default_mass`), and the new temperature is `T + (dt/Cth)·dT`, clamped to `[0, 6000] K`.
  - Double-buffered (`T_curr`/`T_next`, O(1) swap); this is the **only** thermodynamic
    model — no latent heat, no temperature-dependent curves. (The NIST Shomate /
    conductivity tables in `/old` are not used.)

### Registration API

- **Primary: data-driven JSON** (datapack), reloadable via `/reload`:
  - `data/<ns>/orge/materials/<id>.json` — the constant property set.
- **Block → material is the FIRST-TOUCH name-match rule** (no tag bindings, no
  blockstate predicates, no overrides): a block id `<ns>:<path>` first-touches to
  `orge:<path>` if such a material is registered, else the global fallback
  `orge:generic_solid`. `minecraft:water → orge:water`, `minecraft:magma_block →
  orge:magma_block`, `minecraft:diamond_ore` (miss) → `orge:generic_solid`. The rule runs
  **only for a cell with no stored material** (freshly generated / never simulated) or an
  explicit player PLACE — never to re-derive identity for an already-stored cell (§5), and
  never for BREAK (which records durable `orge:vacuum`).
- **Secondary: a thin Java registration event/API** for mods that register in code.
- **Coverage** for ~1000+ blocks without hand-authoring: the name-match rule + a single
  **global fallback material** (`orge:generic_solid`). Because identity is durably stored,
  the lossy block→material map only ever needs to bootstrap an unseen cell.
- Blockstate-gated heat sources (lit campfire, powered redstone, …) are **not** modelled —
  the name-match rule can't read blockstate, so they fall to inert `generic_solid`
  (accepted regression); unconditional emitters keep their material by id (`torch`,
  `glowstone`, `magma_block`, `nether_portal`, …).

## 7. Phase change

- Evaluated **server-side at the second boundary**, after reading back the engine's new
  temperatures and material species (`matOut`).
- If a cell's temperature crosses its material's `min_temp` / `max_temp`, the cell's
  **material changes to `min_target` / `max_target`** — both are **material ids** (e.g.
  `water` → `orge:steam`, `water` → `orge:ice`). The block actually drawn is then the new
  material's `representative_block` (a separate material → block lookup). **Mass and
  temperature carry across unchanged** — both are authoritative per-cell quantities in the
  unified fluid model, conserved exactly across the transition.
- No latent-heat plateau — phase change is an instantaneous threshold crossing by design.
- **Phase is material → material; rendering is material → block.** Every target reuses an
  existing block via `representative_block` (`orge:ice → minecraft:ice`,
  `orge:stone → minecraft:stone`). **ORGE registers no blocks of its own.** A concept vanilla
  has no block for — an invisible gas — is just a material whose `representative_block` is
  `minecraft:air`: `orge:steam → minecraft:air`. Steam is a full fluid in the unified model
  (its own `molar_mass`/`viscosity`/`min_mass`/`max_mass`), not an inert marker; it sorts and
  flows like any other material — it simply has no visible block. Identity never depends on the
  drawn block: it is durable per-cell in the §5 `SectionStore`, so no carrier block is needed.

## 8. Scheduler

Each server tick:

1. Build the **union of all players' spheres** (+ force-loaded regions).
2. Assign each **unique** subchunk to the **nearest healthy worker** whose range covers
   it (best locality → cheapest halos; single owner → natural dedup, "no two players
   compute the same subchunk").
3. If the nearest worker is overloaded/throttled, the subchunk **overflows** to the
   next-nearest covering worker, else to the **server fallback**.
4. Send each worker its assigned subchunks. Geometry (material + mass arrays) is sent
   **once per section version** and cached client-side keyed by version; each tick only
   the mutable **temperature array + neighbor halo** is sent.
5. A subchunk whose result **misses the 1 s deadline holds its previous temperatures**
   for one tick (no recompute storm). Fallback engine only steps worker-less
   force-loaded regions.

**Health throttle:** a worker that delivers late/incomplete drops its range by 1 (down
to a minimum); after K consecutive on-time ticks under a compute-time budget it climbs
back by 1 toward the server's configured default/max range.

## 9. Trust model

Clients return temperatures the server persists as authoritative. The base mod does
**no recompute**; it validates cheaply before saving each value:

1. **Assigned?** Discard results for subchunks not currently assigned to that client.
2. **Finite?** Reject `NaN`/`±Inf` (keep the previous value) so corruption can't spread.
3. **In range?** Clamp temperature to the engine's `[0, 6000]` K and mass to
   `[0, sane max]`.

Stops accidental corruption and gross griefing while preserving the performance win.
Recompute/cross-check verification is a possible future opt-in for hardened servers.

## 10. Phase 2 — fluid dynamics (DONE)

- **Phase-2a (DONE):** a **fluid pass** in ORGE-ENGINE — after conduction, redistribute **mass** by
  gravity + viscosity-limited spread among same-material fluid cells. Mass = fluid level ⇒ finite water
  with **no source blocks**; Java reconciles cell mass back to water levels and suppresses vanilla flow.
- **Phase-2b (DONE, then rebuilt):** density-driven **displacement** unified "fluid spreads into air",
  "gas buoyancy", and "liquid sorting" into one rule. This was subsequently **superseded by the unified
  fluid model** (`docs/superpowers/specs/2026-06-01-unified-fluid-engine-design.md`): one
  section-agnostic, viscosity-gated, molar-mass-sorted advection — material = 6 physics floats
  (`min_mass ≤ default_mass ≤ max_mass`, molar_mass, viscosity, conductivity, heat_capacity);
  immovability is `viscosity == +∞`. The kernel reports `matOut`; mass is authoritative and conserved.

**Phase 2 closes the *physics* roadmap.** Heat transfer stays the constant-property finite-difference
conduction of §2/§6 — no latent heat, no temperature-dependent material curves. Everything past here
(§11) is **gameplay content** on top of the frozen core, then **distribution** (§3) — not new
thermodynamics.

---

## 11. Roadmap — Phase 3+ (what to do next)

Phases 1–2 deliver the thermal + fluid **core** (single-node). The rest is **gameplay content
first, distribution last**: the content layers are independent Java over `SectionStore`/item NBT
and need **no engine change**, so they ship playable value on the current single-node sim; the §3
client-worker distribution is the final, largest lift.

Every item below is an idea note in `docs/superpowers/notes/`; **none has a spec/plan yet.** Each
goes through the proven loop: `brainstorming → spec (docs/superpowers/specs) → writing-plans
(docs/superpowers/plans) → subagent-driven-development`, committed per task on `rebuild`.

### Phase 3 — World forcing (sources & sinks)
Give the world dynamic heat and mass so the sim is *alive*, via one shared per-second heightmap pass.
- **Solar / radiational thermal seeding** (`notes/2026-05-31-thermal-seeding-dimensions`): day/night
  heat in-out; Overworld cycles, End = permanent heat sink, Nether = insulated.
- **Rain mass seeding + evaporation** (`notes/2026-05-31-rain-mass-seeding`): rain adds surface water
  mass; daytime evaporation removes it (biome-scaled). Shares the heightmap pass with solar.
- **Thirsty farmland** (`notes/2026-05-31-thirsty-farmland`): farmland drains water mass on hydrate.
- **Delivers:** living hydrology + a day/night thermal cycle. Foundation for survival (Phase 5).

### Phase 4 — Fluid handling & containers
Player-facing finite-fluid tools; all **mass-conservative** `SectionStore` ↔ NBT transfers carrying
temperature.
- **Dripstone conduit** (`notes/2026-06-02-dripstone-conduit`): moves one `min_mass` quantum
  top→bottom, molar-sort gated, into a cauldron — instead of vanilla "fluid from nothing".
- **Fluid containers** (`notes/2026-06-02-fluid-containers`): bucket (1000 kg, any flowable) · glass
  bottle (250 kg) · cauldron (1000 kg block); fill shown via vanilla durability / cauldron-level
  visuals (no new assets).
- **Delivers:** carry / store / pour finite fluid. The glass bottle is the water source for Phase 5.

### Phase 5 — Player & entity survival (homeostasis)
- **Entity / player homeostasis** (`notes/2026-05-31-entity-homeostasis`): entities get body temp +
  thermal mass + metabolism. Player bars (vanilla HUD assets, damage-only): **Hunger** = heating fuel,
  **Thirst** = cooling fuel (refilled by the Phase-4 bottle / cup-from-cell), reworked mass-based
  **Oxygen** (consumes 200 g air/s from the head cell; sealed rooms suffocate via the engine's Y-column
  molar sort). Vanilla hunger-draining actions (sprint/jump/attack) add exercise heat.
- **Depends on:** Phase 3 (meaningful ambient temps) + Phase 4 (drinking). **Delivers:** the survival
  payoff that makes the thermal world matter.

### Phase 6 — Geology
- **Lava cooling branches** (`notes/2026-05-31-lava-cooling-branches`): phase-change target chosen by
  cooling rate (fast→obsidian, mid→basalt, slow→stone) via material `cooling_branches[]`; flips the
  `OrgeFluidPolicy` lava-cooling hook on under ORGE control.
- Small §7 enrichment; independent, can slot earlier if desired.

### Phase 7 — Distribution (the §3 rebuild goal)
- **Client-worker networking**: server assigns sections, clients compute, results flow hub-and-spoke
  over the Wire-protocol payloads below (`ASSIGN`/`GEOMETRY`/`STEP_INPUT`/`STEP_RESULT`/`HEALTH`).
  Stubs already exist (`OrgePackets`, the client entrypoints, `OrgeNeoForge` handlers).
- **§9 recompute / cross-check verification**: the anti-cheat opt-in (§9), now relevant because
  untrusted clients compute results.
- **Delivers:** the server-load reduction that motivated the whole rebuild. Largest lift, and **last**
  because the single-node sim carries Phases 3–6.

### Cross-cutting backlog (non-phased)
- **Multi-platform native builds** (`notes/2026-05-29-native-packaging`): `.dll` / `.dylib` + all
  `{os}×{arch}` `.so` via ORGE-ENGINE CI. Today only linux-x64 runs the real engine; other platforms
  fall back to `StubEngine`.
- **Engineering hardening / perf** (in-code TODOs): `SectionData` UNIFORM-demote on save; first-touch
  promotion churn (`MinecraftThermalWorld.setAllTemperatures`); per-server-config read range
  (`ReadRangeProvider`); deeply-immutable material snapshots (`ActiveMaterials`).

---

## Wire protocol (server ↔ worker), v2 sketch

Replaces the old JSON-line socket protocol. In-process FFI means the *engine* call is a
direct function invocation; the *network* protocol below is server↔client over Minecraft
custom payloads.

- `ASSIGN`     — list of subchunk keys this client owns next tick, with version stamps.
- `GEOMETRY`   — (on demand) material-index + mass arrays for a section version.
- `STEP_INPUT` — per assigned subchunk: temperature array + neighbor halo.
- `STEP_RESULT`— per assigned subchunk: new temperature (and Phase-2 mass) array.
- `HEALTH`     — client-reported compute time / deadline status (drives throttling).

## Build & native integration notes

- A small JNI bridge (`orge_jni.cpp`) over the header-only `orge_kernel.hpp` builds
  `liborge.{so,dll,dylib}` (SDL-free). No jextract / generated bindings — the single
  `native double orgeStep(...)` method is hand-declared in `NativeEngine`.
- Gradle task fetches pinned `liborge-*` artifacts into
  `src/main/resources/natives/<os>-<arch>/`; runtime extracts to a temp dir and
  `System.load`s the match (mirrors the old `SimServerManager` extraction trick, minus
  the subprocess).
