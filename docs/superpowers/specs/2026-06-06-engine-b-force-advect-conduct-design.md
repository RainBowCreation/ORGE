# Engine-B Rebuild — "Force → Advect → Conduct" (unified Bingham constitutive law)

**Status:** RATIFIED (brainstorm 2026-06-06). Supersedes the `E = ρ·h·w` energy-vector
formulation of `2026-06-04-engine-b-unified-formula.md` / `…-velocity-field-design.md`
for the *mechanical core*. Reuses the same World/Chunk/snapshot/LUT scaffolding.

## 0. Motivation

The shipped engine-B transports a single **energy-flux vector** `E = ρ·h·w_drive`, where
`h = cp·T + p/ρ + ½|u|²` bundles thermal energy, pressure work, and kinetic energy *inside the
momentum carrier*. This "mixes force and heat together": you cannot reason about, or audit,
mass transport without also reasoning about temperature. It is correct but opaque, and the
open bugs (#7 leveling, #2 air over-accumulation) are hard to localise.

This rebuild **un-mixes** the channels via operator splitting into three single-responsibility
GPU passes, and **unifies** the material model so every cell runs one branchless constitutive
law regardless of "phase". No code ever asks "is this a solid / liquid / gas".

## 1. Decisions (all ratified)

| # | Decision | Choice |
|---|---|---|
| D1 | Motion model | **Inertial** — velocity is persistent per-cell state |
| D2 | Pressure / incompressibility | **Local pseudo-compressible EOS** (no global Poisson solve) |
| D3 | Heat storage | Store **`T`** on disk; advect **energy `E = mass·cp·T`** inside the engine (energy is a within-step intermediate, **zero extra stored field**) |
| D4 | Velocity persistence | Persist **quantized 3×int16**; only for active cells |
| D5 | Pass structure | **3 passes: Force → Advect → Conduct**; conservation isolated in Advect |
| D6 | Buoyancy / thermal expansion | **Emergent** from gravity + pressure-gradient — NOT separate force terms |
| D7 | Solids vs fluids | **No tiers, no phase branch.** `yield_stress` is a universal material axis; one Bingham/Herschel-Bulkley law for every cell |
| D8 | Yield criterion | **Net-force magnitude** now, behind a **pluggable criterion seam** for Mohr-Coulomb later |

## 2. Per-cell state & storage

**Persistent (disk / Java SectionStore), active cells only:**

| Field | Type | Notes |
|---|---|---|
| `matID` | uint16 | index into resident material LUT |
| `mass` | float (or uint16 scaled) | kg |
| `T` | float (or uint16 @0.1 K) | kelvin |
| `vx,vy,vz` | **3×int16 (quantized)** | D4; dropped for dormant/static cells |

Static stone/air and dormant cells carry **no** velocity/energy — the working set is the active
fluid frontier (thousands–low-millions of cells), never "billions of blocks". Field-shaving saves
MB; **dormancy + sparse storage saves the GB**.

**Derived on load into the working snapshot (transient, not stored):**
`ρ = mass / V`, `E = mass · cp · T`.

**Material LUT fields (a few dozen entries, NOT per-cell — zero per-cell cost):**
`cp` (heat capacity), `default_mass`, `min_mass`, `max_mass`, `viscosity μ`, **`yield_stress`**,
conductivity `k`. Compressibility descriptor `chi = (max−default)/(max−min) ∈ [0,1]` derived from
the mass triple (0 = incompressible/liquid-like, 1 = gas).

Every material is a **point in continuous (yield, viscosity, compressibility, …) space**:

| | water | lava | mud | sand | stone | bedrock |
|---|---|---|---|---|---|---|
| `yield_stress` | 0 | low | mid | mid | huge | ∞ |
| `viscosity` | low | high | high | low | high | ∞ |
| `chi` | mid | low | low | ~0 | 0 | 0 |

## 3. The three passes (one tick = A → B → C)

All three are **stencil maps**: read ≤6 neighbours, write own cell. No global reduction, no
Poisson solve → embarrassingly parallel, one GPU thread per active cell, three kernels per tick.
This is the "extremely fast on GPU" payoff and is *only* possible because of D2 (local EOS).

### Pass A — Force → velocity  *(reads 6 neighbour pressures + velocities; writes own `v`)*

```
p_i   = eos_pressure(mat, mass_i, default_mass(mat, T_i))      // local, no neighbours
                                                              //   default_mass lowered by heat (thermal expansion)
F     = -g·ŷ                                                   // gravity (per unit mass: a_grav)
      + -(1/ρ_i)·∇p      from (p_j − p_i) over 6 faces         // pressure gradient
      + a_ext            from JNI external-force array          // explosions, player push
                                                              //   (∇²v viscous term folded into dragScale below)
# --- unified Bingham / Herschel-Bulkley constitutive law (D7), every cell, branchless ---
excess = max(0, |F| − yield_stress_i)                          // must exceed threshold before ANY motion
a_eff  = (|F| > eps) ? (excess / |F|) · F : 0                  // direction preserved, magnitude reduced
v_i   ← (v_i + dt·a_eff) · dragScale(viscosity_i, ρ_i, dt)     // viscosity damps the RATE
v_i   ← CFL_clamp(v_i)                                         // |v|·dt ≤ dx  — cannot skip a cell
```

- `dragScale = 1/(1 + dt·λ)`, `λ = μ/(ρ·dx²)` (reused from engine-B). `μ = ∞ → dragScale = 0`.
- **Buoyancy, thermal convection, and incompressibility all emerge from the single `∇p` term** (D6).
  `default_mass(mat, T)` makes heavy species build more pressure (sink, push light up) and hot
  fluid read as over-full (expand) — heat and molar mass are **inputs to pressure**, never separate forces.
- **Yield criterion is a pluggable function** `yields(F, cell) → excess` (D8). v1 = net-force
  magnitude; later Mohr-Coulomb = `cohesion + friction·confining_pressure` on the shear component.
- **Free-slip walls are emergent, not labelled:** a high-`yield` neighbour simply has `v ≈ 0`, so its
  face reflects pressure (does no advective work). Bedrock = `yield = ∞`, same law, extreme number.

### Pass B — Advect  *(THE conservation pass; antisymmetric face fluxes)*

```
for each face(i,j):
    u_face = ½(v_i + v_j) · n̂                                  // normal component
    donor  = upwind(u_face)
    ṁ      = ρ_donor · u_face · A · dt
    ṁ      = clamp(ṁ, donor_budget, receiver_room)             // reuse engine-B clamps (§C.5)
    ΔE     = ṁ · cp_donor · T_donor                            // HEAT rides with the mass
    Δp     = ṁ · v_donor                                       // MOMENTUM rides with the mass (inertia)
    apply ±ṁ, ±ΔE, ±Δp antisymmetrically to i and j
mass,E,momentum ← snapshot + Σfaces
v = momentum / mass ;   T = E / (mass·cp)
```

- **Conservation lives here and ONLY here.** Grand mass, per-species mass, and grand energy are
  exact-by-construction (antisymmetric fluxes). Passes A and C never move mass.
- **Heat advecting inside `ΔE` makes the temp-ghost (audit #3) structurally impossible**: a
  near-empty cell carries near-zero energy, so it cannot retain a stale hot temperature.
- **Cross-species transport reuses the Stage-2 energy-lowering SWAP** (PE-gate + §2.4 absorb/reflect
  on `chi`). The swap fires on the energy/chi **numbers**, not a phase label, so a shoved high-yield
  solid triggers it through the same gate every cell uses — no "solids swap, fluids flux" branch (D7).
  A swap is a pure permutation ⇒ fabrication/vanishing of mass is impossible.
- **GPU form:** gather (each cell recomputes its 6 face fluxes from the read-only snapshot — both
  sides see the identical formula, deterministic, no atomics).

### Pass C — Conduct  *(per cell; neighbour scalar exchange; no vectors)*

```
ΔE_i = dt · Σfaces k · (T_j − T_i) · A / dx                    // Fourier, antisymmetric → energy-exact
T_i  = clamp(E_i/(mass_i·cp), stencil_min, stencil_max)        // KEEP audit-#3 discrete-maximum-principle clamp
```

Solids conduct too. Conduction moves **energy** antisymmetrically (conserves grand energy); `T` is
re-derived after. The max-principle clamp is retained verbatim (forward-Euler is unstable on
advection-thinned ~1e-6 kg cells without it).

**Write-back:** `mass`, `T = E/(mass·cp)`, `v` (int16-quantized).

## 4. Conservation invariants (the gates)

- **Grand mass exact** (`Σ mass` const) — Pass B antisymmetric fluxes + permutation swaps.
- **Per-species mass exact** — swaps are permutations; same-species merges and vacuum-fill conserve.
- **Grand energy exact** under advection (Pass B carries `ΔE`) + conduction (Pass C antisymmetric).
- **Momentum** is advected; gravity/pressure/external add or remove it (physical — not globally conserved).
- **Bounds:** `mass ∈ [0, max_mass]` (receiver-room clamp), `T ∈ stencil` (max-principle clamp),
  `|v|·dt ≤ dx` (CFL). No explosions, no negative mass, no ghosts.

## 5. Reuse vs rewrite

**Reused from engine-B:** World/Chunk/snapshot/LUT, `chi`/`eos_pressure` (→ Pass A), donor-budget &
receiver-room clamps (→ Pass B), Stage-2 PE-gate swap (→ Pass B cross-species), conduction
max-principle clamp (→ Pass C), the int16 velocity quantization plumbing.

**Removed:** the `E = ρ·h·w` energy-flux vector and Decrypt's "re-partition energy into mass +
velocity". Velocity becomes **primary state**, not something recovered from a bundled vector. This
is the "un-mixing".

**Added:** material-LUT `yield_stress` + the pluggable `yields()` criterion; the Bingham constitutive
line in Pass A; explicit `ΔE`/`Δp` carry in Pass B.

## 6. How this addresses the open bugs

- **#7 leveling (self-fixes):** a taller same-species column has higher EOS pressure at depth →
  `∇p` in Pass A pushes laterally into the shorter column → it self-levels. Hydrostatic head
  **emerges** from local-EOS + gravity; the engine-A overburden/head-pressure hacks are deleted.
- **#2 air over-accumulation (becomes calibration, not structural):** gas (`chi≈1`) now has a real
  expansion pressure resisting compaction; bounding it is a `max_mass` + EOS-stiffness tuning task
  in the calibration stage, not a structural fix.

## 7. Emergent behaviour the unified law buys for free

Bingham + local-EOS gives, with no special-case code: water dams that **burst** when head pressure
exceeds the toe block's `yield_stress`; **sand piling to an angle of repose then avalanching**
(low yield); **landslides / structural collapse**; **mud, wet concrete, lava-with-crust, toothpaste**
(materials with *both* yield and viscosity — the general case, not an edge case).

**Calibration watch-item:** a failing block redistributes load to neighbours and can cascade
(avalanche). Realistic, but Pass A/B must rate-limit per tick (CFL + per-step displacement bound) so
the cascade does not destabilise. Mohr-Coulomb upgrade (pressure-strengthened deep blocks) lands via
the `yields()` seam in the calibration stage.

## 8. Out of scope (this spec)

Numerical calibration of `K`, `γ`, `α`, `yield_stress`, conductivity, and quantization scales
(separate calibration stage). The Mohr-Coulomb criterion (seam only here). Client-side GPU dispatch
wiring (the math is authored GPU-portable; server CPU fallback runs the same stencils).
