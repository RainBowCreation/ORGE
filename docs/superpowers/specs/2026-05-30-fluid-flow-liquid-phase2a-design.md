# §10 Phase-2a — Mass-conservative liquid flow (water + lava), native engine — Design Spec

**Status:** approved (brainstorm 2026-05-30) — ready for `writing-plans`.

## Goal

Begin DESIGN §10 (Phase 2) with its foundational slice: make **mass move between cells**,
conservatively, so that water and lava **flow** and the heat they carry advects with them.
This is the first consumer of an authoritative, conserved mass field — the change the
current pure-conduction kernel cannot express (today `mass` is a read-only input to
`liborge`; only temperature comes back).

Scope of this slice: **liquid water and lava flow** — mass-conservative fall + viscosity-
limited horizontal spread, enthalpy carried with moving mass, and reconciliation of cell
mass to vanilla `minecraft:water` / `minecraft:lava` render levels. ORGE becomes the **sole
authority** over these fluids (vanilla fluid ticking suppressed); water/lava are **finite**
(no infinite refill). Gas buoyancy, latent heat, and finite-water entry points are explicitly
deferred to follow-on specs (see Out of scope).

## Core principle: all physics in the engine

ORGE's identity is that the **native `liborge` engine is the single physics authority**, run
over snapshots via FFI and kept bit-identical between its two C++ surfaces. Phase-2a honors
that: **advection is implemented natively, not in Java.** The clean split is

- **Engine (C++)** owns *all* physics — conduction (as today) **and** the new advection
  (mass movement + the heat carried with it).
- **Java** owns *only* Minecraft integration — snapshot/writeback, §9 validation, and the
  block-level reconciliation/suppression the engine cannot know about.

There is **no Java advection** and no separate Java oracle; the C++ reference sim
(`sim_engine.hpp`, with its SDL visualizer) is the reference, and the stateless kernel
(`orge_kernel.hpp`) is kept bit-identical to it — exactly the discipline the FFI track
established for conduction.

## Decisions (resolved in brainstorm)

1. **Native, not Java-first.** Advection lives in the engine. Development workflow mirrors how
   conduction was built: implement advection in `sim_engine.hpp` first (watch it flow in the
   `sim_render.hpp` SDL visualizer while tuning), then port into the stateless
   `orge_kernel.hpp` kept **bit-identical**, then expose through JNI. (This reverses the
   earlier "phased Java-first" idea: the C++ side already has a full dev/test/visualization
   environment — `sim_engine.hpp` already carries `mass_kg` arrays, `sim_render.hpp` renders,
   `ORGE-ENGINE/tests/` exists — so there is no reason to prototype the physics in Java.)

2. **Where it runs in the cycle.** The native `step()` now performs conduction **and**
   advection in one call, updating both `T` and `mass`. The Java scheduler cycle is unchanged
   in shape, only in payload:

   ```
   snapshot(T + mass)
     → native step()                    [conduction + advection — BOTH in liborge]
     → §9 validate (+ mass-conservation invariant)
     → writeBack(T + mass)              [mass now flows back, not just T]
     → reconcile: PhaseChanger (§7, unchanged)  +  FluidReconciler (NEW: mass → render level)
   ```

   Within the kernel, conduction is applied first (within-cell/contact heat exchange), then
   advection (bulk-moves mass and the heat attached to it): a deliberate, documented order.

3. **JNI / ABI change.** `orgeStep(…)` today returns only `Tout` and takes a temperature halo.
   It grows a **`haloMass`** input and a **`massOut`** output. Affected:
   `orge_kernel.hpp::step_section_with_halo` (signature + body), `orge_jni.cpp` (new pinned
   arrays, reverse-order release), and the hand-declared `NativeEngine.orgeStep` native method
   on the Java side. This is a **breaking ABI bump** of `liborge` → new pinned artifact version.

4. **The advection rule (physics, native).** Per fluid cell, mass `m`, capacity
   `M_full = material.defaultMass` (full 1 m³ cell):
   - **Fall (gravity):** transfer down into the cell below, up to its remaining capacity.
   - **Horizontal spread:** flux toward lower-mass horizontal neighbours, rate-limited by
     **viscosity** (already a reserved `Material` field, Pa·s):
     `transfer_fraction = clamp(k / viscosity, 0, CFL-safe cap)`. Lava (high viscosity)
     spreads slowly; water fast. This is §10's "gravity + viscosity-limited spread."

5. **Cross-section conservation — mass-carrying halos + antisymmetric face-flux.** The kernel
   steps one 16³ section with **read-only** halos. Mass conserves across section seams only if
   the halo carries neighbour **mass** and each boundary face flux is computed **identically
   from both sides** (`Φ(A→B) = −Φ(B→A)`), so each section writes only its own cells yet the
   cross-seam exchange nets to zero globally. Interior transfers use a flux-accumulation buffer
   so the result is order-independent. `Σmass` invariant. **This is the core native algorithm
   to get right**, and it is what makes the design forward-compatible with the §3 distributed-
   worker model.

6. **Enthalpy transport.** When `Δm` moves from A (temp `T_A`) into B:
   `T_B ← (m_B·T_B + Δm·T_A)/(m_B + Δm)`; A's temperature (intensive) is unchanged ⇒ energy
   conserved. When a cell drains to ≈0 it becomes empty/air and its temperature is reset
   (meaningless without mass). Computed in the kernel, not Java.

7. **Material separation — no overlap with §7.** Advection moves mass **only** between
   same-material fluid cells and empty/air. Water flowing onto lava does **not** merge masses;
   the water-on-lava → steam/stone interaction is **already owned by §7 phase-change** and
   stays there. Clean seam.

8. **Block representation — reuse vanilla, suppress vanilla physics.** Render via
   `minecraft:water` / `minecraft:lava` (honours the §7 "reuse vanilla for existing concepts"
   rule). ORGE is the **sole authority**: a `VanillaFluidSuppressor` cancels vanilla liquid
   scheduled/random ticks and neighbour-update spread for managed fluid blocks. This is the
   most invasive part and carries a **known risk** (see below).

9. **Render-level mapping.** `LEVEL` becomes a pure visual-depth readout of mass fraction
   `f = m/M_full`: `f ≥ ~0.95 → 0` (full), else `level = round((1−f)·7)` clamped 1–7; falling
   flag when the cell below has capacity. Lava analogous. "Source" semantics are gone (finite
   water) — level is just depth. Java-side (the engine knows nothing of blocks).

10. **Reconcile seam.** `FluidReconcileLogic` (pure) + `FluidReconciler` (MC adapter),
    mirroring the `MinecraftPhaseChanger` pattern: post-step, map cell mass → block render
    level, place a block on mass gain, remove on mass ≈ 0. Runs on the server thread after
    write-back, beside the existing §7 `PhaseChanger`.

11. **§9 validation (Java safety net).** Add to the existing validate/reject path:
    `Σmass_after == Σmass_before` (±ε·N over the region; boundary in/out = 0) and
    `0 ≤ m_cell ≤ M_full + ε`. Violation → reject the step via the existing reject path + log.
    This is the integration-side guard that catches any native advection bug in-game; the
    primary correctness proof lives in the C++ tests.

12. **Boundary.** Unloaded/unsimulated neighbours are **no-flow walls** — mass cannot leave the
    simulated region, so conservation holds within it. Known limitation (water piles at the
    loaded edge); revisited when seeding / region-streaming is designed.

13. **Where the mass comes from (finite water, no seeding pass this slice).** Water/lava mass
    is supplied by the **existing block-derived path** (§5 + the B+C `writeBack` fix `b4a79d5`)
    and by `/orge fill` / `/orge set` for deterministic testing. ORGE mass is authoritative and
    vanilla never refills ⇒ finite. Eager conservative seeding-on-chunk-load is **deferred**.

14. **Fluid flag.** Add a small `fluid: true` boolean to material JSON (§6 material-model) to
    mark which materials participate in advection (water, lava), alongside the already-reserved
    `viscosity`. The kernel needs the flag (or an equivalent fluid-conductivity-style LUT
    entry) passed in the material LUT.

## Components

### Engine (C++, `ORGE-ENGINE/` repo)
| Component | Purpose |
|---|---|
| `sim_engine.hpp` advection | Reference implementation over the full-world `mass_kg` arrays; fall + viscosity spread + enthalpy. Visualized via `sim_render.hpp` (SDL) during development. |
| `orge_kernel.hpp::step_section_with_halo` | Stateless per-section conduction **+ advection**, kept **bit-identical** to `sim_engine.hpp`. New mass halo + mass-out; antisymmetric face-flux. |
| `orge_jni.cpp` | Zero-copy bridge updated for `haloMass` in + `massOut` out, pinned/released in order. |
| `ORGE-ENGINE/tests/` | C++ tests: conservation, settling, spread, viscosity contrast, enthalpy, no-flow boundary. |

### Java (main repo)
| Component | Layer | Purpose |
|---|---|---|
| `NativeEngine.orgeStep` | core | hand-declared native method updated to the new ABI (mass in/out). |
| scheduler writeBack | scheduler | now writes `massOut` back into `SectionData`, not just `T`. |
| §9 mass guard | core | `Σmass` + per-cell bounds invariant on the validate/reject path. |
| `FluidMaterials` | core + data | reads the new `fluid` flag + viscosity from `Material` data; builds the LUT passed to the engine. |
| `FluidReconcileLogic` / `FluidReconciler` | pure + MC adapter | cell mass → `minecraft:water`/`lava` level; place on gain, remove on ≈ 0. Mirrors `MinecraftPhaseChanger`. |
| `VanillaFluidSuppressor` | both loaders | cancel vanilla liquid ticks + spread for managed fluid blocks. `ExpectPlatform`. The invasive bit. |

No new Java storage — `mass[]` already lives in `SectionData` (§5).

## Cross-repo & build

This slice spans **two git repos**: `ORGE-ENGINE/` (kernel, JNI, reference sim, tests) and the
main repo (native-method declaration, scheduler/§9/reconcile/suppressor/material-flag). The
Gradle native-fetch task must pin and pull the **new `liborge-*` artifact version** built from
the changed kernel. Plan must sequence: land + test the engine change → build/publish the new
`liborge` artifacts → bump the pinned version in the main repo → wire the Java side.

## Known risk — vanilla fluid suppression

Architectury common events do **not** expose vanilla fluid-tick cancellation, and no mixin
toolchain is currently set up in this repo. `VanillaFluidSuppressor` may therefore require
introducing a mixin dependency per loader. The plan should treat the suppression *mechanism*
as a per-loader implementation detail behind the `ExpectPlatform` seam and call out the mixin
follow-up explicitly rather than assuming an event-only solution exists.

## Testing

- **C++ (`ORGE-ENGINE/tests/`, primary correctness):** conservation (random fields, `Σmass`
  stable over N steps) · settling (column → hydrostatic, no oscillation) · spread (stack →
  flat pool) · viscosity (lava wets fewer cells than water after K steps) · enthalpy (hot mass
  into cold cell raises T by the conserving formula; energy conserved) · no-flow boundary ·
  cross-section conservation (mass crossing a section seam is exactly conserved). SDL visual
  spot-check during development.
- **Bit-identicality:** kernel vs `sim_engine.hpp` produce identical mass+T fields (extends the
  FFI track's existing bit-identical check to the mass output).
- **Java:** §9 mass-conservation guard test · `FluidReconcileLogic` mapping test (fraction →
  level; ≈0 → removed) · integration (extend headless `AuditScenarioTest`, against the new
  `liborge`): water above a gap falls + spreads + reconciles; water next to lava still steams
  via §7.

## Multiloader

`FluidReconcileLogic` is pure core. `VanillaFluidSuppressor` + the `FluidReconciler` MC adapter
use `ExpectPlatform` across `fabric-1.21` + `neoforge-1.21`.

## Out of scope (deferred)

- **Gas buoyancy / diffusion** (steam rising) — same native pass with an inverted gravity term,
  later spec; closes the §7 steam loop.
- **Latent heat** — phase-change energy plateaus. Banked direction: keep `T` primary + a per-cell
  latent-energy accumulator; like advection it will be **native** (new per-cell state + halo/JNI
  plumbing). Couples to §7. Chosen as the next track after Phase-2a's in-game audit.
- **Eager seeding-on-chunk-load**, **buckets / player interactions, rain** (water entry/exit).
- **Water ↔ lava interaction** beyond what §7 already does.
- **Region-boundary water streaming** (no-flow wall for now).
- **Temperature-dependent material curves.**
