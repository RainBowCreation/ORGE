# Brief: Make the thermal core observable & drivable (engine-audit track)

**Date:** 2026-05-30 · **Branch:** `rebuild` · **Status:** brief / seed for brainstorming (no spec/plan yet)

## Why (the goal)

The v1 thermal core is functionally wired end-to-end (§6 materials → §5 store → §2 engine →
§8 scheduler → §7 phase change), but **you cannot actually watch it work or verify the numbers**.
Today, loading a world gives: a flat placeholder 285 K everywhere, no heat sources, and no way to
see temperatures — so conduction has no gradients to spread, water never boils, and the only visible
effect is an artifact (lava instantly turns to stone, ice melts) driven by the flat ambient.

Before building the big remaining phases (multi-worker distribution §3, Phase-2 fluids §10), we want
to **audit the core engine and its conduction/phase calculations** in-game. That requires three small
subsystems that, together, let us inject a known input, watch it evolve, and confirm the output:

> **Audit scenario:** place water next to lava, watch the water heat over several seconds, cross
> 373.15 K, and turn to `orge:steam` — and confirm the per-second temperatures match the expected
> finite-difference conduction math. Equally: a hot cell next to cold cells should diffuse at the
> rate the kernel predicts.

These 3 topics are a **pre-phase audit track**. Each (or the set) goes through the proven loop:
`brainstorming → spec (docs/superpowers/specs) → writing-plans (docs/superpowers/plans) →
subagent-driven-development`. Build directly on `rebuild`, commit-per-task, push `origin/rebuild`,
no PR/merge-to-main while v1 is incomplete.

**Meta-question for the brainstorm:** one combined "engine-audit" track with three parts, or three
independent specs/plans? They share a material-field addition (see Topic B/C), which argues for
coordinating them; but Topic A is cleanly independent.

---

## Topic A — Observability (see + inject temperatures)

**Purpose:** read (and ideally set) a cell's temperature/mass so we can both *audit* and *demo* the
sim. This is the foundation — without it nothing else is verifiable. Likely **first**.

**Candidate approaches (to grill):**
- **Server command** `/orge temp [get|set] <pos>` (+ `set` doubles as a manual test injector / heat
  source) and maybe `/orge section <pos>` to dump a whole 16³. Cheapest, most precise for auditing;
  no client/render code. Strong recommendation for the audit use-case.
- **Thermometer item** — right-click a block to print its cell temp/mass in chat. Player-friendly.
- **Debug HUD / F3 line** showing the targeted block's cell temp/mass (client-side).
- **Heatmap overlay / block tint** — client render that colors blocks by temperature. Best "feel",
  most work, hardest to test headless (no `runClient` in the sandbox).

**Open design questions:** command vs item vs HUD vs overlay (pick the audit-critical one first;
others optional)? read-only vs read+write (write = test injection)? single-cell vs section dump?
client-side rendering at all in v1, or keep it server-side/text to stay headless-testable?

**Couples to:** §5 `SectionStore`/`SectionData` (read/write cells on the server thread), §8 dimension
routing. A pure server command is fully unit-testable and needs no client.

---

## Topic B — Ambient & initial temperatures (kill the flat 285 K)

**Purpose:** seed cells at sensible temperatures instead of a flat 285 K, so the world is physically
plausible and lava doesn't instantly "freeze." Wires up the **deferred `AmbientProvider`** (§5 TODO).

**Candidate approaches (to grill):**
- **Biome-derived ambient:** map biome temperature → K at section generation (replace
  `AmbientProvider.FALLBACK`). Snowy ≈ 260 K, temperate ≈ 285 K, desert/nether ≈ hotter.
- **Per-material natural temperature:** a new material JSON field (e.g. `default_temperature` /
  `equilibrium_temperature`) so lava seeds ~1400 K, ice ~260 K, etc. — the cell's starting temp comes
  from its block's material, not a global ambient. (This is the clean fix for lava→stone.)
- Combination: material natural temp where defined, biome ambient otherwise.

**Open design questions:** biome-temp → Kelvin formula? add a material temperature field (couples to
§6 `Material` + `MaterialCodec`)? only seed never-simulated sections (so we don't overwrite an evolved
gradient)? how to treat already-generated worlds (seed on first simulation touch)?

**Couples to:** §5 `AmbientProvider`/`SectionData` seeding, §6 material model (new field), §8 snapshot
(where ambient is currently filled at 285 K).

---

## Topic C — Heat sources (create gradients; enable boiling)

**Purpose:** give the sim energy to move. Without sources, conduction only relaxes toward a flat
equilibrium and water never boils. This is what makes the audit scenario possible.

**Candidate approaches (to grill):**
- **Fixed-temperature ("pinned"/Dirichlet) sources:** a material flag marks certain cells as held at
  a fixed temperature (lava pinned at ~1400 K, ice/snow at ~260 K, fire hot). Each tick Java re-pins
  source cells to their fixed temp *before* the next snapshot; the engine diffuses from them. **Likely
  needs no C++ engine change** — it stays in the existing scheduler/§5 seams (set the source cells,
  step, repeat). Elegant; also subsumes Topic B's lava problem.
- **Power-injection (Neumann) sources:** a material "heat output" (W) adds energy to a cell/neighbors
  each second. More physical for things like a furnace, but needs an energy→ΔT calc and possibly an
  engine change.

**Open design questions:** fixed-temperature vs power-injection (or both)? does it require an engine
(`liborge`) change, or can Java re-pin source cells each tick around the existing `step()` (preferred
— keeps the native kernel untouched)? which vanilla blocks are sources (lava, fire, magma, torches,
furnaces lit, campfire…) and at what temps? interaction with §7 (a pinned lava cell shouldn't phase-
change itself)?

**Couples to:** §6 material model (source/temperature fields), §8 scheduler (re-pin step), §7 phase
change (exempt pinned sources or order correctly), §2 engine (only if power-injection is chosen).

---

## Suggested sequencing (to confirm in brainstorm)

1. **A (observability)** first — you can't audit without seeing/injecting. A server `get/set` command
   alone unlocks manual engine auditing (set a hot cell, watch it diffuse) with zero other work.
2. **C (heat sources)** next — fixed-temperature sources give real gradients and make lava/ice sane,
   enabling the water-boils-next-to-lava demo. If "fixed-temperature" is chosen it largely covers B's
   lava case too.
3. **B (ambient/init)** to round out the rest of the world's baseline temperatures.

(A → C → B, or A → B → C — the brainstorm decides. A is firmly first.)

## Explicitly out of scope (later phases)

Multi-worker distribution + wire protocol (§3 / DESIGN "Wire protocol"); Phase-2 fluid dynamics —
flow, viscosity, buoyancy, no-source-block water, latent heat, realistic curves (§10). These come
**after** the engine is audited and trusted.

## References

DESIGN.md (§2 engine, §4 cadence, §5 persistence, §6 materials, §7 phase, §8 scheduler).
Memories: `orge-engine-ffi`, `orge-section-store`, `orge-material-model`, `orge-scheduler`,
`orge-phase-change`. Build env + conventions: see the §7 plan
(`docs/superpowers/plans/2026-05-30-phase-change-track.md`).
