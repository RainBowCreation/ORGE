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
  subchunks + a 1-cell neighbor halo (temps + material indices) + the material LUT + dt,
  run **one** step and return new temperatures (and, in Phase 2, mass).
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

- Each cell stores **only `temperature` (K) and `mass` (kg)**. Material identity is
  **not** stored per cell — it is derived from the block via the material map. (So the
  blockstate itself encodes which material a cell is.)
- In memory: two parallel `float[4096]` arrays per loaded section.
- On disk: a **separate compressed region store** under `world/orge/` (e.g.
  `r.<x>.<z>.orge`), loaded/unloaded alongside the chunk. The vanilla `.mca` files are
  never touched.
- Each section is serialized as either:
  - **UNIFORM** — one temperature + one mass (the common case for sections far from any
    heat source), or
  - **FULL** — `deflate/zstd(T[4096])` + `deflate/zstd(mass[4096])`, once a gradient
    forms.
- A never-simulated section is implicitly `UNIFORM(biome-ambient T, material defaultMass)`
  and costs ~nothing. Ambient T is derived from biome temperature at generation, with a
  fixed fallback (~285 K).
- `mass` is treated as **fluid level** from day one (1000 kg ≈ a full 1 m³ water block),
  so Phase-2 fluid dynamics need no storage rework.

## 6. Material model

Flat **constants** per material (no temperature-dependent curves in v1):

| field | meaning |
|---|---|
| `thermalConductivity` | W/(m·K) |
| `heatCapacity` | J/(kg·K) |
| `viscosity` | Pa·s (reserved for Phase-2 fluid flow) |
| `defaultMass` | kg per 1 m³ cell |
| `molarMass` | kg/mol |
| `boilingPoint` / `freezingPoint` | K |
| `boilingTarget` / `freezingTarget` | material id to become |
| representative block | block placed when something *becomes* this material |

- The rich NIST Shomate / conductivity-table data in `/old` is retained as the seed for
  a future "realistic curves" addon, not used by v1.

### Registration API

- **Primary: data-driven JSON** (datapack), reloadable via `/reload`:
  - `data/<ns>/orge/materials/<id>.json` — the constant property set.
  - bindings — block→material, by **tag** (e.g. `#c:stones → orge:stone`) plus
    **per-block overrides** (e.g. `minecraft:iron_block → orge:iron`).
- **Secondary: a thin Java registration event/API** for mods that register in code.
- **Coverage** for ~1000+ blocks without hand-authoring: tag bindings + overrides + a
  single **global fallback material** (`orge:generic_solid`). Air, water, lava are
  explicitly mapped.

## 7. Phase change

- Evaluated **server-side at the second boundary**, after reading back new temps.
- If a cell's temperature crosses its material's `boilingPoint` / `freezingPoint`, the
  block is replaced with the **target material's representative block**, carrying
  **mass and final temperature** across unchanged (mass is conserved exactly, even when
  the resulting density is unrealistic).
- No latent-heat plateau in v1 (possible future refinement).
- **New blocks only for genuinely new concepts.** `boiling_target`/`freezing_target` name
  the **block to place**. Targets that vanilla already has are **overrides, not new blocks**:
  water → `minecraft:ice` (freeze) / ice → `minecraft:water` (melt), lava → `minecraft:stone`
  (freeze). Core registers a **new block only for a concept vanilla lacks** — gases/fluids:
  **`orge:steam`** (water → steam, steam → water). In v1 `orge:steam` is an **inert, non-ticking,
  non-colliding marker** — its temperature lives in the per-cell `SectionData` arrays, not in
  blockstate/NBT; it has no buoyancy/flow until Phase 2. Core adds **no decorative/solid blocks**.
- **Carry temperature, not mass, in v1.** Temperature is the simulated authority (it lives in
  `SectionData` untied to block identity, so it carries across a swap automatically). Mass
  conservation is deferred to Phase 2, when mass becomes authoritative (§8 v1 derives per-cell
  mass from `Material.defaultMass`).

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

## 10. Phase 2 (deferred)

- Add a **fluid pass** to ORGE-ENGINE: after conduction, redistribute **mass** by
  gravity + viscosity-limited spread. Mass = fluid level ⇒ finite water with **no source
  blocks**. Java reconciles cell mass back to water levels and removes source blocks.
- Give gas blocks (`orge:steam`, …) buoyancy via the same pass.
- Optional: latent-heat plateaus; realistic temperature-dependent material curves
  (seeded from `/old`).

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
