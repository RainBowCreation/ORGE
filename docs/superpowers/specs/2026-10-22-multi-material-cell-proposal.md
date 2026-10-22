# Multi-material cell: design proposal (2026-10-22, rev 2)

Status: **PROPOSED, awaiting the user's ratification.** Nothing implemented. Docs only.
Issue #25 (Slice A of PRD #24). Subordinate to `DESIGN-LAW.md` (v4.4); where this text and the law disagree,
the law wins until the user amends it. This file does **not** edit the law: §10 is the proposed replacement
text for law #7 / #8 / #9 plus the one-line consequential edits to #2 / #4 / #6 that the replacement forces.
Rev 2 folds in an adversarial review of rev 1. It fixes 16 defects: ENCODE locality, the Darcy cap, the
saturated rest state, frame gating, exact parity, the moving phase threshold, slot exhaustion, shared
receiver room, the retention mixing rule, ε-slot energy, mixture `P₀`, the mixed-cell swap key, and the
walkthrough arithmetic.

Hard constraints from PRD #24 (cited below as **C1–C5**): C1 vanilla-client joinable, C2 GPU-friendly
(fixed-size SoA, branchless, 1-hop), C3 the law's invariants per component, C4 saved worlds keep loading,
C5 real data only.

---

## 1. The problem (verified on `rebuild` @ 8a9c1c9)

A cell is exactly one material: law #7 stores one `matIx` and one `mass`; law #8's LUT describes that one
material; §6.3 of the working spec says cross-species mass moves **only** by whole-cell swap. So:

| Wanted | Why it cannot happen today |
|---|---|
| Water evaporates **into** an air cell (humidity), condenses back (dew, fog, rain) | The air cell cannot hold a second species. Water→steam is only the 373 K plateau relabel of the whole cell. |
| Rain soaks **into** dirt/sand, drains out | A dirt cell holds dirt only. `dirt.json` (1500 kg/m³) is "full" although real loam is ~43 % pore space. |
| Air/water pass **through** a waterlogged, flowable or permeable block without deleting it | `docs/material-data-sources.md`: *"Waterlogged blocks ignore their water: treating them as water would let a drain delete the block."* A rail (157 kg, 2 % of its volume) blocks all flow. |

Engine and Java facts this proposal builds on:
- Persisted per cell today (`sim_engine.hpp` `Chunk`): `matIx u16, mass f32, px/py/pz f32, E f64, P f32,
  swapReady f32, swapPartnerKey i32` = **38 B** (+ `T_curr/T_next` f32 derived caches, 8 B).
- Kernels per tick: `6 + 2·N_relax` (v4 §6.1: ENCODE, `2·N_relax` half-sweeps, R0, R1, R1.5, R2, DECODE).
- Save format: `SectionCodec.FORMAT_VERSION = 5`, per section `{E, mass, palette + u16 indices, momentum,
  P}`; v1–v4 are rejected on read.
- Render level: `FluidReconcileLogic.levelForFraction(f)`: `f ≥ full → 0`, else `round((1−f)·7)`
  clamped to 1..7.
- Advection receiver choice (`sim_engine.hpp` ~743) sorts by `molarMass` (lighter is displaced).
- Partial blocks already carry "substance density × filled volume" as `default_mass`
  (`stone_stairs` 2025 = ¾·2700, `oak_slab` 375 = ½·750, `rail` 157.48 = 0.02·7870). The open volume is
  in the data already; nothing reads it.

---

## 2. What any design must preserve

- **One formula per component, zero classification (law #0).** No "host", "pore fluid", "solvent" role may
  be a branch. Roles must fall out of continuous data weights, the way `χ` already does.
- **Single `P` per cell (law #1), one force vector (law #2), same rule in 6 directions (law #3),
  ENCODE→RESOLVE→DECODE with 1-hop reads (law #4).** ENCODE and DECODE read only their own cell (plus
  #4's no-penetration clamp).
- **Store extensive, derive intensive (law #7).** No stored `T`, no stored velocity, no stored saturation.
- **Relabel/phase transfer is identity on `E` (law #6 chain anchoring).**
- **Per-species mass exact.** As today, a phase pair (water/ice/steam) is one chemical species across a
  relabel; the ledger counts it that way.

---

## 3. Alternatives

### (a) K-slot composition, full per-slot state — `(matIx, m, E)` per slot, K = 2–4
Each slot is a sub-cell with its own enthalpy (and, to be complete, its own momentum).
- + Fully general; each component has its own `T`.
- − K temperatures per cell need an intra-cell heat-exchange term with its own stiff time scale. A 1 m³
  cell of sand + water equilibrates in seconds, so explicit coupling at dt = 0.5 s needs an implicit
  solve.
- − Per-slot momentum = a K-fluid model with interphase drag, also stiff. Without per-slot momentum, (a)
  collapses into (c) anyway.
- − Memory: K = 4 → `4·(2+4+8)` = 56 B for the composition vs 14 B today (+42 B), +36 B more with
  per-slot momentum.

### (b) Host material + fixed continuous fill channels (pore gas / pore liquid / vapor / dissolved)
One host `(matIx, m)` plus four globally-fixed channels: pore-liquid mass, pore-gas mass, vapor mass,
dissolved mass.
- + Cheapest (+16 B), simplest slot routing (none).
- − The channel species are hard-wired (water, air, steam, salt). The engine must know *which* `matIx` is
  "water": that is a material-name dependency (law #0 forbids naming materials in the engine).
- − Host and channels move by **different** formulas (host by whole-cell flux/swap, channels by seepage):
  two systems, so it fails law #0 and the drift test (d) in spirit.
- − Not general: lava cannot run through a rail cell, honey cannot soak into sand, and no future fluid can
  use the mechanism without a new channel (a law #8 change each time).

### (d) Carrier + passenger (K = 2) — the 2026-09-30 proposal's §10.2
- + +6 B, one extra channel.
- − K = 2 cannot hold sand + pore water + pore air at once. Water entering dry sand would need the air to
  leave first, but the air has nowhere to go inside the cell's 2 slots. Fails the infiltration and
  through-flow requirements.

### (c) **RECOMMENDED — K-slot mixture cell**: per-slot `(matIx, m)` only, shared `E`, momentum and `P`
K = 4 unordered slots, each just a species id and a mass. The cell keeps **one** enthalpy `E` (one `T`,
local thermal equilibrium), **one** momentum, and **one** `P` (the pressure of its fluid content). Motion
of one component relative to the mixture is a per-component **slip flux** (seepage through a porous
skeleton, sedimentation, counter-current air/water). Liquid↔vapor exchange is a per-face **vapor flux**.
This is the drift-flux mixture model (Ishii 1975; Manninen, Taivassalo & Kallio 1996) with Darcy drag
for the porous skeleton. "Skeleton", "pore fluid" and "gas" are not roles: they are continuous data
weights (§4.1), so every slot runs the same formula.
- + One formula for every component; roles emerge from data like `χ` does.
- + Shared `E` makes evaporation, condensation and melting **in-cell mass transfers at fixed E**: exact by
  construction, with evaporative cooling and latent release falling out of the re-derived `T`.
- + A one-slot cell reduces **exactly** to today (§4.9). Old worlds load as K = 1.
- − Slot routing and slot exhaustion (a fifth species cannot enter) need rules (§4.6).
- − ~2× transient bandwidth in R1/R1.5/R2 (§7).

### Comparison against PRD #24 constraints

| | C1 vanilla client | C2 GPU / branchless | C3 invariants per component | C4 old worlds | Memory Δ (persisted) | Verdict |
|---|---|---|---|---|---|---|
| (a) K-slot full state | ✓ (server-only) | ✗ stiff intra-cell `T` coupling, K momenta | ✓ but energy exchange needs an implicit solve | ✓ K = 1 | +42 B (+78 B w/ momentum) | too heavy |
| (b) host + channels | ✓ | ✓ fixed, but engine names species | ✓ for the 4 hard-wired species only | ✓ | +16 B | fails law #0 / generality |
| (d) carrier + passenger | ✓ | ✓ | ✓ | ✓ | +6 B | cannot do infiltration |
| **(c) K-slot mixture** | ✓ | ✓ fixed K, masks, 0 new kernels | ✓ per component, one `E` | ✓ K = 1 = today | **+18 B** (K = 4) | **recommended** |

---

## 4. The recommended model (c)

### 4.1 State, new data, derived quantities
Persisted per cell: `ix[k], m[k]` for `k = 0..K−1` (K = 4, unordered; an empty slot has `m[k] = 0`),
`p⃗ = (px,py,pz)`, `E`, `P`, `swapReady` (+ its partner key), `void_ix`.

New LUT columns (all defaulting to today's behavior):
- `ρ_p` (**particle density**, kg/m³): the density of the part of the material that fluids cannot enter.
  For a partial block it is the substance's density (stairs 2700, oak slab 750, rail 7870); for a
  granular soil it is the grain density (2650); for a liquid it equals `defaultMass`. **Default
  `ρ_p = defaultMass`.**
- `r` (**flow resistance** = 1/κ at default packing, m⁻²): the inverse intrinsic permeability of a cell
  filled with this material's skeleton. **Default 0.**
- `p_b, λ_BC, S_r` (Brooks–Corey retention, §4.10). **Defaults 0, 1, 0.**

Constants: `P_atm = 101 325 Pa` (the pressure at which the LUT's phase temperatures are measured);
regularizers `ε, ε_V, r_ε`.

Derived in ENCODE from the cell's own state only (never stored). Here `n_k = m_k / M_k` (moles) and
`m₀,k = defaultMass_k`:
```
v_k      = (1 − χ_k) · m_k / ρ_p,k                     // condensed volume [m³]; a gas ≈ 0 by (1 − χ)
s_k      = r_k / (r_k + r_ε)                            // skeleton weight ∈ [0,1], continuous, like χ
V_skel   = Σ_k s_k · v_k                                // volume of the porous skeleton
V_pore   = max(ε_V, V − V_skel)                         // volume fluids may enter
V_k^free = max(ε_V, V − Σ_{j≠k} v_j)                    // volume available to k (excludes k's own volume)
S        = clamp(Σ_k (1 − s_k)·v_k / V_pore, 0, 1)      // condensed non-skeleton fill of the pores
r_cell   = Σ_k r_k · m_k / m₀,k                         // cell resistance, fill-weighted
θ_k      = v̂_k / Σ_j v̂_j ,  v̂_k = v_k + χ_k·(V − Σ_j v_j)·n_k/(Σ_j χ_j n_j + ε)   // volume share, Σθ = 1
σ_k      = component k's own soft-wall term (v4.4 D3 M3, own fill only)  →  yf_k = max(0, σ_k − τ_y,k)/(σ_k + ε)
```
Sand, dirt, stairs, a rail or a flower get `s ≈ 1` from their `r > 0`. Water, air, lava, ice and stone
(left at `r = 0`) get `s = 0`. `s` marks only *where fluid can seep and whose weight rests on contacts*.
**Whether a component moves is decided by yield (`τ_y`) and viscosity alone, as today**, never by `s`.

### 4.2 Temperature: one `T` from one `E`
`E = Σ_k m_k · h_k^eff(T)`. `h_k^eff` is component `k`'s own curve below its phase threshold `T*_k` and
its partner `t`'s chain-anchored curve above it. At `T*_k` it jumps by the **gap**
`G_k = h_t(T*_k) − h_k(T*_k)`. For a fixed-temperature pair `G = L`. For a pair whose threshold moves
(§4.7), `G = L + Δcp·(T_b − T*)` (Kirchhoff), so `T` is continuous across the transfer.

The mixture curve `H(T)` is monotone and piecewise linear, with ≤ 2K breakpoints `T*`. They are taken
from the **previous tick** (cached, lagged one tick), so they are fixed constants inside the inverse. The
inverse is exact and branchless:
1. Sort the ≤ 8 breakpoints with a fixed 19-exchange sorting network (`min`/`max` only).
2. Compute `T = T₀ + Σ_seg clamp((E − H(seg_start)) / C_seg, 0, ΔT_seg)`.

Each jump is a zero-width segment of height `m_k·G_k`. Where `E` falls inside a jump, `T` is pinned at
that `T*` and the **band position** `x_k = (E − H(T*⁻)) / (m_k·G_k) ∈ [0,1]` is the lever-rule fraction
paid. When K = 1 the network is a no-op and this is today's `h⁻¹(E/m)`.

### 4.3 Pressure: still one `P`
`P` is the pressure of the cell's fluid content (the pore pressure). The relaxation (v4 §3) is unchanged
in form. Its inputs generalize:
- **Hydrostatic density** in law #2's face terms = the **intrinsic pore-fluid density**
  `ρ_f = Σ_k (1 − s_k)·m_k / V_pore`. The face area is porosity-weighted:
  `A_f = A · min(V_pore,i, V_pore,j)/V`.
  - In saturated sand the pore water then has a hydrostatic gradient of `ρ_w·g`, so it rests (no slip).
    This is Terzaghi's effective stress: the skeleton's weight rests on grain contacts, not on `P`.
  - With `s = 0` (every current row), `ρ_f = M/V` and `A_f = A`: today.
- **EOS** (law #9) becomes Dalton's law. Only components with high `χ` contribute, by weight:
  ```
  p_k      = n_k · R · T / V_k^free                                // partial pressure of k
  γ_k      = χ_k / (max_j χ_j + ε)                                  // relative gas weight; 1 for a one-slot cell
  P₀,cell  = Σ_k γ_k n_k P₀,k / (Σ_k γ_k n_k + ε)                   // mole-weighted rest pressure
  p_eos    = Σ_k γ_k · p_k − P₀,cell
  χ_cell   = Σ_k θ_k · χ_k                                         // gas volume share; = χ for one slot
  source   = χ_cell · α_eos · (p_eos − P)                          // replaces χ·α_eos·(p_eos − P)
  ```
  With one slot: `γ = 1`, `V^free = V`, `θ = 1`, so `source = χ·α·((m/M)RT/V − P₀ − P)`, **exactly**
  today. Water + air: water's `γ ≈ 0`, so liquid adds no gas pressure, and air's partial pressure is over
  the volume the water leaves it.
- **Volume wall** (extends INV-SOFTWIN): `W_V = K_V · max(0, Σ_k v_k − V)` joins the per-component walls.
  Condensed matter cannot occupy more than the cell; this caps pore water at the pore volume (dirt:
  0.434 m³ → 434 kg) with no "pore capacity" rule.
- **Per-component soft window** (v4.4) is kept per slot:
  - `Wh_k = K_k·max(0, m_k − max_k)`;
  - `Wl_k = K_k·(1−χ_k)·max(0, a_k·min_k − m_k)`, with `a_k = clamp(1 − Σ_{j≠k} v_j / V, 0, 1)`.

  The cohesion floor scales with the volume left to `k`, so 50 kg of pore water in sand is not
  "under-min". With one slot, `a = 1`.

### 4.4 Momentum, gravity, Darcy drag
- `p⃗` is the cell's momentum and `u⃗ = p⃗ / (M + ε)` with `M = Σ m_k`, as today.
- **ENCODE** (local) applies gravity to all of it once (`p⃗ += −g·M·dt·ŷ`, + external impulse), as today.
- ENCODE then applies the existing viscous drag and a new **Darcy drag** in the same implicit form:
  `p⃗ ← p⃗ / (1 + dt·μ_f·r_held/ρ_f)`, with `r_held = Σ_k r_k·(m_k/m₀,k)·(1 − yf_k)`, i.e. only a
  skeleton that has *not* yielded resists. `μ_f` is the volume-weighted viscosity of the non-skeleton
  content.
  - The lost kinetic energy is deposited in the cell's `E`. This is the same drag→heat deposit as
    `vel_damp`, so energy stays exact.
  - Darcy's law is the terminal velocity of this balance: `u = κ·ρ_f·g/μ_f` = the hydraulic conductivity
    `K_s`.
  - `r = 0` gives a factor of 1: parity. A sand cell that yields (a slump) has `yf → 1` and is not
    Darcy-resisted, since its pore fluid moves with it.
- **DECODE** applies v4.4's Bingham momentum rule with the mass-weighted mixture of the per-component
  weights. For one slot this is v4.4's rule unchanged.

### 4.5 Mass motion: ONE per-component face flux (RESOLVE)
For face `i→j` and component species `k`, every term is antisymmetric and is computed from identical
buffer values on both sides:
```
f_k = ψ_k · y_k,up · F_ij                                        // (1) bulk: today's momentum flux, carried by mobile composition
    + ψ_k · ρ̂_k,up · q'_k                                        // (2) slip: per-component potential flow, volume-zero-sum
    + χ_pair,k · g_vap · (p*_k,i − p*_k,j) · A · dt              // (3) vapor exchange (0 for a pair with no gas side, by data)

ψ_k   = max(0, |F_face| − τ_y,k) / (|F_face| + ε)                   // law #8's yield gate per component, at this face (R1 knows F_face)
(1)  F_ij  = today's legalized face mass flux from p⃗ (v4 §6), area A_f
     y_k   = ψ_k·m_k / (Σ_j ψ_j·m_j + ε)                           // share of the moving content
(2)  Φ_k   = P − (1 − χ_k)·p_c + ρ̂_k · g · z                        // capillary suction on condensed fluid, by weight
     λ_k   = k_r,k / (μ_k · (½(r_cell,i + r_cell,j) + r_ε))
     q_k   = −λ_k · (Φ_k,j − Φ_k,i) · A_f/Δx · dt                   // volume flux of k
     q'_k  = q_k − φ_k · Σ_j ψ_j q_j ,  φ_k = ψ_k v̂_k / Σ_j ψ_j v̂_j  // φ from ONE cell (the upwind cell of Σq)
     ρ̂_k   = m_k / (v̂_k + ε)                                        // in-situ density of k
(3)  §4.7
```
- **The skeleton never slips.** `ψ = 0` for a component whose yield its face drive does not beat
  (stairs, rail, resting dirt, ice, stone), so it neither slips nor is carried. Taking φ from one cell
  makes `Σ_k ψ_k q'_k = 0` exactly.
- **Parity.** One slot gives `y = 1`, `φ = 1` and `q' = q − q = 0`, and term (3) is 0 for a lone
  component. The result is today's flux (Darcy acts through §4.4's drag, which is 1 at `r = 0`).
- **Counter-current flow.** Water slips down while air slips up, with net volume zero; the bulk flux
  carries only the remainder. This is what lets rain enter a sand cell full of air.
- **Boundary face** (receiver holds no liquid of `k`). The liquid potential uses law #2's half-cell ghost,
  blended continuously:
  `Φ_k,face = w_liq,j·Φ_k,j + (1 − w_liq,j)·(P_j + ρ̂_k·g·½(z_j − z_i))`.
  Wet soil over a cave therefore drips only once its pore water is at positive pressure at the face (a
  seepage face, §5 W2).
- **Upwinding** (`up`) is a select on the sign of a face flux. That is arithmetic on a flux, not on a
  material category, the same as today's donor selection.
- **Enthalpy** carried: `Σ_k f_k · h_k(T_up)` (term (3) carries `h_vapor(T_donor)`).
  **Momentum** carried: `(Σ_k f_k) · u⃗_up`.
- **Donor budget, per component (R1):**
  - `budget_k = m_k − a_k·min_k`, with today's full-drain exception;
  - `σ_k^D = min(1, budget_k / Σ_faces f_k^out)`.
- **Receiver room (R1.5), per component AND shared.**
  - Mass room for each `k`: `room_k = avail_k · max(0, max_k − m_k)`.
  - One volume room the condensed inflows share: `room_V = max(0, V − Σ_k v_k)`.
  - Scales: `ρ'_k = min(1, room_k / Σ_in f_k)` and
    `ρ'_V = min(1, room_V / Σ_k Σ_in (1−χ_k)·f_k/ρ_p,k)`.
  - The committed inflow of `k` is scaled by `ρ'_k·((1−χ_k)·ρ'_V + χ_k)`. The cell therefore cannot be
    over-filled by several inflows at once; gases are bounded by their `max` and the EOS.
- **Whole-cell swap** (R0, v4 §7) stays, with all K slots in the payload. The driver uses the cell's mixture
  density `M/V` (with the β term) and the mean molar mass `M/Σ n_k`; for one slot these are today's.
  The swap remains the path when neither cell has open volume (lava over water). §6.3's "cross-species
  movement = swap ONLY" is superseded: species now also flux into another cell's open volume.

### 4.6 Slot routing and reservation (fixed K, masks)
- `avail_k,j = 1` when the receiver `j` holds species `k` in some slot (`ix == ix_k`), or when `j` grants
  `k` an empty slot this tick. Otherwise it is 0, so the room is 0 and the flux is clamped, never dropped.
- **Grant (R1.5).** Each receiver grants **one** empty slot per tick, to the inbound species with the
  largest Σ intent (tie → lower `matIx`). Donors read the grant 1-hop in R2.
- **Reservation.** A receiver never grants its *last* empty slot while one of its own components has a
  phase partner that is not resident. That slot is kept for the phase change (§4.7).
- Matching is `ix == ix'`: species identity bookkeeping, the same comparison today's same-species flux
  makes. It never asks what a material *is*.
- **ε-slots.** DECODE empties any slot with `m_k < ε_mass`. Its mass and its energy share
  `ΔE = m_k·h_k(T)` go to the boundary ledger, which is today's ε rule made per component. This sink is
  listed in the proposed #9.
- Slots are unordered; nothing in the engine has a "host slot".

### 4.7 Phase change per component (one rule, three regimes by data)
Every component `k` with a phase partner `t` (today's quadruple) uses:
```
χ_pair  = max(χ_k, χ_t)                                          // 0 for water↔ice, ≈1 for water↔steam
p_ref,k = χ_k · p_k + (1 − χ_k) · (P_atm + P)                     // a vapor sees its partial pressure; a liquid sees total P
T*_k    = (1 − χ_pair) · T_data,k + χ_pair · T_sat,k(p_ref,k + ε_p)   // threshold, lagged one tick (§4.2)
```
- **(P1) Latent transfer, DECODE, local.** Where `T` is pinned at `T*_k` (§4.2), move `Δm = x_k·m_k` from
  slot `k` to `t`'s slot.
  - **ΔE ≡ 0**: `x·m·h_t(T*) + (1−x)·m·h_k(T*) = m·h_k(T*) + x·m·G_k`, which is exactly `E` on the band.
    Slot `t` lands at `T*`, so `T` is continuous.
  - **No slot for `t`** (the §4.6 reservation makes this rare): the band is open-ended. Its height is
    `m_k·G_k/(avail_t + ε)`, so `T` stays pinned at `T*`, `E` keeps accumulating exactly, and the transfer
    completes when a slot frees. Energy is never wrong; the species is late, never wrong.
  - This one rule gives three behaviors:
    - melting and freezing at the fixed data temperature (`χ_pair = 0`);
    - **boiling at `T_sat(P_atm + P)`**: 373 K at gauge 0 (today's value), ≈ 394 K under 10 m of water
      (real);
    - **condensation at the dew point**: a vapor's threshold is `T_sat(p_v)`. Humid air cooled below its
      dew point condenses, and the latent release holds it on the dew point. The dew point itself falls
      as `p_v` drops, so `T` tracks it downward (lagged `T*`).
- **(P2) Sub-saturation evaporation into the cell's own gas, DECODE, local.**
  `Δm = θ_ph · min(m_k, χ_pair · g_vap · A_int · max(0, p_sat,k(T) − p_t) · dt)`, liquid `k` → vapor `t`, at
  fixed `E`.
  - `A_int = A·w_liq,k·w_gas`, with `w_liq,k = clamp(v_k / V_film, 0, 1)`,
    `w_gas = clamp((V − Σ v)/V_film, 0, 1)` and `θ_ph = 1 − exp(−dt/τ_ph)`.
  - The re-derived `T` falls: evaporative cooling, exact.
- **(P3) Face vapor exchange, RESOLVE: term (3) of §4.5.**
  - `p*_k = w_liq,k·p_sat,k(T) + (1 − w_liq,k)·p_t`, where `p_t` is the cell's partial pressure of `k`'s
    vapor partner.
  - The flux leaves the donor from its liquid share `w_liq,i` and its vapor share `1 − w_liq,i`. It lands
    in the receiver as liquid (share `w_liq,j`) or vapor (`1 − w_liq,j`), and always carries
    `h_vapor(T_donor)`.
  - So the donor pays latent heat when liquid leaves as vapor, and the receiver gains it when vapor lands
    on its liquid (a cold lake: `p*_cold = p_sat(T_cold) < p_v`). Antisymmetric in mass and `E`.
  - Dew on **dry** grass needs no special case. The radiatively cooled grass cell's own air drops below
    its dew point, and P1 condenses its vapor in place.
- **Saturation curve from existing columns only** (no new data): integrated Clausius–Clapeyron with
  Kirchhoff's `L(T) = L + Δcp·(T_b − T)`, `Δcp = cp_liquid − cp_vapor`, `T_b` = the pair's `maxTemp`,
  `A₀ = L + Δcp·T_b`:
  `ln(p_sat/P_atm) = (M/R)·[A₀·(1/T_b − 1/T) − Δcp·ln(T/T_b)]`.
  Its inverse `T_sat(p)` is 2 fixed Newton steps from the constant-L closed form (branchless, < 0.1 K).
  Check against IAPWS (Wagner & Pruß 2002): 283 K → 1.30 kPa (real 1.23, +6 %); 293 K → 2.46 kPa (real
  2.34, +5 %); 373 K → 101.3 kPa (exact by construction).

`max(χ_k, χ_t)` and the `(1−χ)` blends are continuous multipliers on data. No component is classified as
volatile.

### 4.8 Conduction and radiation
`k_cell = exp(Σ_k θ_k·ln k_k)` (Lichtenecker geometric mean, the standard soil-conductivity mixing rule;
Johansen 1975, Farouki 1981), with the volume shares `θ` (Σθ = 1). The face term is today's harmonic mean
of the two cells. `ε_cell = Σ_k θ_k·ε_k`, so a mostly-gas cell is mostly transparent by weight. With one
slot `θ = 1`, so `k_cell = k` and `ε_cell = ε` for any fill, including a 300 kg water cell.

### 4.9 Single-component parity (what the migration relies on)
Take a cell with one occupied slot, the default `ρ_p`, and `r = 0`:
- `s = 0`, `ρ_f = M/V`, `A_f = A`, `V^free = V`, `γ = θ = 1`, `χ_cell = χ`, `P₀,cell = P₀`, `a = 1`.
- Darcy drag factor 1, `y = φ = 1`, `q' = 0`, term (3) = 0.
- `k_cell = k`, `ε_cell = ε`, swap key = today's. The sorting network collapses to `h⁻¹(E/m)`.

Every formula reduces **exactly** (same operations on the same values) to v4.4, with one stated
exception: a vapor pair (`χ_pair ≈ 1`) relabels at `T_sat(P_atm + P)` instead of the fixed 373 K. That is
the same at gauge 0 and moves with depth (§12 Q3 offers the exact-parity variant).

### 4.10 Retention (capillarity), new data, real
Brooks–Corey (1964):
```
S_e     = clamp((S − S_r)/(1 − S_r), 0, 1)
p_c     = p_b · (S_e + ε_S)^(−1/λ)
k_r^BC  = (1 − χ_k)·S_e^((2+3λ)/λ) + χ_k·(1−S_e)²·(1 − S_e^((2+λ)/λ))   // liquid / gas branch blended by χ
k_r,k   = (1 − φ_s) + φ_s · k_r^BC ,  φ_s = V_skel / V                    // no skeleton ⇒ k_r = 1 (free fall, free flow)
```
- **Parameters.** `p_b, λ, S_r` of a cell are the skeleton-weighted means of its components'
  (`Σ_k s_k m_k X_k / (Σ_k s_k m_k + ε)`). A cell with no skeleton has `p_c = 0`.
- **Evaluation cell.** `k_r` is evaluated in the **upwind** cell of the component's flux (standard
  upstream mobility weighting). `p_c` is evaluated in each cell from its own `S`.
- **Defaults** `p_b = 0, λ = 1, S_r = 0`, plus `φ_s = 0`, give `k_r = 1` and `p_c = 0`: today.

---

## 5. End-to-end walkthroughs (recommended model; g = 10, V = A = dx = 1, dt = 0.5 s)

### W1 — Water evaporates into an air cell; vapor condenses back
Water cell W (1000 kg, 293 K) under air cell A (`air` 1.2 kg, empty slots, 293 K, dry).
1. **ENCODE.** A: `p_steam = 0`. W: `w_liq = 1`, `p_sat(293) = 2.46 kPa`.
2. **RESOLVE, term (3) on the W→A face.**
   - Penman's still-air coefficient `f(u=0) = 2.6 mm·day⁻¹·kPa⁻¹` gives
     `g_vap = 3.0e-8 kg·m⁻²·s⁻¹·Pa⁻¹`, so the flux is `3.0e-8 · 2457 = 7.4e-5 kg/s`, i.e. 3.7e-5 kg per
     tick.
   - W loses that much water and `f·h_steam(293)`. A grants its empty slot to `steam` and gains the same
     mass and E. Per-species (H₂O) and grand E are exact.
   - Latent drawn from W: `7.4e-5 · 2.45e6 ≈ 180 W/m²`, a real warm-lake evaporative flux.
3. **DECODE.** A's `T` is re-derived (no change: the vapor brought its own enthalpy). W's `T` drops by
   `180/4.19e6 ≈ 4e-5 K/s`.
4. **A saturates** at `m_v = p_sat·M·V/(R·T) = 0.0182 kg` after `0.0182/7.4e-5 ≈ 250 s` (~4 min, if
   nothing carries it away). Humid air (mean molar mass < 0.029) is lighter, so it rises by the existing
   buoyant swap/EOS path. Vapor rides wind in bulk term (1).
5. **Condensation.** At night A is cooled (conduction/radiation) toward 283 K.
   - The same 0.0182 kg of vapor at the cooler `T` exerts 2.37 kPa, so A's dew point is
     `T*_steam = T_sat(2.37 kPa) ≈ 292 K`. A reaches it first.
   - P1 then moves steam → water in A's slots, ΔE ≡ 0. Latent release holds `T` on the dew point, which
     falls as `p_v` falls.
   - Reaching 283 K requires 0.008 kg to condense (vapor 0.0182 → 0.010 kg): ~20 kJ of latent heat that
     the cooling must remove. That condensate is fog.
   - Liquid in a gas cell has `k_r = 1` (no skeleton) and `ρ̂_w ≫ ρ_f`, so slip term (2) moves it down,
     CFL-capped: rain.
6. Over a colder lake, vapor condenses directly onto the water by term (3): dew/fog over water.

### W2 — Infiltration into dirt; drainage
Puddle cell U (water 300 kg + air) over dry dirt D. D holds `dirt` 1500 kg; `ρ_p = 2650` gives
`v = 0.566` and `V_pore = 0.434 m³`, with 0.52 kg of air in the pores. Loam
`κ = K_s·μ/(ρ_w g) = 3.7e-6·1e-3/1e4 = 3.7e-13 m²` (Rawls 1982 `K_s` = 1.32 cm/h), so `r = 1/κ`.
1. **Who moves.** D's dirt has `ψ = 0` (yield not beaten): it neither slips nor is carried. Bulk term (1)
   in D is throttled by D's Darcy drag (§4.4).
2. **Slip term (2), U→D face.**
   - Water's `Φ_w` drops into D by `ρ_w·g·dz` plus D's capillary suction `p_c(S≈0)`, which is large: dry
     soil sucks.
   - The face resistance `½(0 + r_D)` is a half-cell path, so the first-cell rate is `2·K_s`
     (`7.4e-6 m/s`, 3.7e-3 kg per tick), faster still while dry (sorptivity). Deeper in a soil column the
     path is a full cell and the rate is `K_s`.
   - Air's net slip `q'_a` points up: water enters and an equal volume of air leaves upward through the
     same face (counter-current).
3. **Room.** The shared volume room caps D at 434 kg of water. The 300 kg puddle soaks into a loam cell
   in ~11 h at `2K_s`. Sand (`K_s = 21 cm/h`) takes ~45 min. Those are real rates.
4. **Drainage.**
   - *Inside a soil column:* water drains down until suction balances gravity. Brooks–Corey loam (`h_b`
     11.2 cm, λ 0.22) holds `S_e = (h_b/z)^λ ≈ 0.11^0.22 ≈ 0.6` at 1 m above the water table, which is
     field capacity.
   - *Into a cave below* (air cell C, no liquid): the D→C face uses the boundary ghost (§4.5). Water exits
     only when its pore pressure at the face exceeds the cave's, i.e. `ρ_w·g·½dz ≥ p_c(S_e)`:
     `p_c ≤ 5 kPa` ⇒ `S_e ≥ (1.1/5)^0.22 ≈ 0.72`.
   - Soil over a cave drips only once wetter than field capacity: a seepage face, as in real caves.
5. Dirt never moves (its yield is not exceeded), so it is never deleted.

### W3 — Flow through waterlogged, flowable and permeable blocks
- **Stairs (waterloggable).** `stone_stairs` 2025 kg, `ρ_p = 2700` ⇒ `v = 0.75`, open fraction 0.25.
  - The open quarter is a slit: `κ = φ·h²/12` (Hagen–Poiseuille slit with open fraction φ, Bear 1972
    §5.10). With h ≈ 0.5 m, `κ ≈ 5e-3 m²` and `r ≈ 190`.
  - Darcy terminal speed `κρg/μ ≈ 5e4 m/s`, so drag is negligible (CFL binds first).
  - Stairs have `ψ = 0` (τ_y 1.4e8 N), so the stairs slot never moves.
  - Water fluxes in from the side until the shared volume room binds at 250 kg, and drains out the same
    way.
- **Flowable (rail, flowers, grass, torches).** Rail: `v = 157.48/7870 = 0.02`; with h = 1 m,
  `κ = 0.98/12`, so `r ≈ 12`. Air and water pass through, using 98 % of the volume. The rail stays.
- **Permeable (sand, gravel, dirt).** As W2, with each row's real `κ`, `p_b`, `λ_BC`, `S_r`.
- **Air through all of them.** Same terms with air's `μ = 1.8e-5`. Under the same potential gradient air
  is 55× more mobile than water through the same pores (`μ_w/μ_a`), which is real.

### W4 — Boiling and deep water
A pure water cell heated at the surface boils at `T_sat(P_atm + 0) = 373 K` (P1, today's behavior). At
10 m depth (`P ≈ 1e5 Pa` gauge) it boils at ≈ 394 K. Steam produced inside a full cell is in-band (steam
max 1000), and the EOS pushes it out as today: the v4 §8.4 steam explosion is unchanged.

### W5 — Freezing a puddle in a flower cell
Poppy cell with 50 kg water at 273 K. `χ_pair(water, ice) = 0`, so the threshold is the fixed 273 K.
- The cell holds `poppy + water + air` (3 slots). The §4.6 reservation kept the 4th slot free while water
  had no resident `ice` partner, so P1 moves water → `ice`, ΔE ≡ 0.
- Ice volume `50/917 = 0.055 m³` fits.
- Ice has `ψ = 0` at rest (τ_y 2e6 N), so it stays; the poppy stays. The vanilla state is unchanged (§6).

---

## 6. Representative vanilla block state (Java, C1)

Engine slots are unordered and carry no "host" notion. The **host** is what the vanilla world already
shows: the live block. `RepresentativeState.pick(liveState, components)` is pure Java, called once per
reconciled cell. It reuses `FluidReconcileLogic.levelForFraction`:

1. `H` = the live block's material.
2. **Host present** (`H` is among the components with `m > ε`) and `H` is not drawn as a fluid: keep the
   live block, then set its properties.
   - **`waterlogged`:** `waterlogged = hysteresis(v_water / V_pore; on ≥ 0.50, off < 0.25)`.
   - **`layers` (snow):** `layers = clamp(round(8·v_snow/V), 1, 8)`.
   - **Any other property** (rail shape, flower, grass): untouched. The fluid inside is invisible to a
     vanilla client, because vanilla cannot show water in a non-waterloggable block.
3. **Host absent** (melted, drained, eroded), or `H` is air/fluid: draw the condensed component with the
   largest volume.
   - **A fluid** is drawn as its `representative_block` at `level = levelForFraction(v/V)`: full → 0,
     else `round((1−f)·7)`, today's rule.
   - **A solid** is drawn as its block's default state.
   - **No condensed component:** draw air.
4. Never writes a block that is not a vanilla state. The self-write guard (2026-09-30 §4.4) prevents the
   reconciler's own write being captured as a placement.

This replaces "waterlogged blocks ignore their water". A waterlogged stair is `stone_stairs + water` in
the engine, and the client sees `waterlogged=true`. No new blocks, items, entities, registries, assets or
packets.

---

## 7. GPU layout, memory, kernel count (C2)

**Persisted SoA buffers:** `ix[K]` u16, `m[K]` f32, `px,py,pz` f32, `E` f64, `P` f32, `swapReady` f32,
`swapPartnerKey` i32. Each is a fixed array of K planes: no per-cell allocation, no variable-length lists.

| | today | K = 3 | **K = 4** |
|---|---|---|---|
| persisted B/cell | 38 | 50 | **56** (+18) |
| transient B/cell (R1–R2: `f[6]`, σ, ρ', room_V, grant, swap choice/mutual) | ~37 | ~106 | **~138** |
| derived caches (T, lagged `T*[K]`, `S, p_c, V_pore, ρ_f`, `s_k`, `θ_k`) | 8 | 60 | 72 |
| 1 M cells, all buffers | ~83 MB | ~216 MB | ~266 MB |

**Kernel count delta: 0.** It stays `6 + 2·N_relax`:
- the new per-cell derivations and the Darcy drag ride ENCODE;
- the three flux terms ride R1/R1.5/R2 (term (3) is one more face term in the same loop), and the slot
  grant rides R1.5;
- P1/P2 and ε-slot emptying ride DECODE.

**Cost.** Per-kernel work grows ~K×, and the R-passes are bandwidth-bound, so expect ~2–2.5× today's
14-kernel time. The v4 §9 perf gate (≤ 2 ms / 1 M cells / step) must be re-measured. If it fails, the
levers are K = 3 (drops the 4th slot, so no salt; §9) or fp16 transient `f`.

**Branchless:** slot routing is `==` masks plus a min-index; the T inverse is a sorting network; every
weight (`s, ψ, χ_pair, γ, θ, w_liq, yf`) is a saturating ratio or `max`/`clamp`. A one-slot cell runs the
same instructions with the empty slots contributing 0.

---

## 8. Save-format migration (C4)

- **Format.** `SectionCodec.FORMAT_VERSION` goes 5 → **6**.
  - A v6 section adds a `slotCount` byte (1..K).
  - Each slot ≥ 1 gets a palette-indexed `u16` species array and an `f32` mass array (deflated).
  - Slot 0 is v5's existing palette + mass arrays, byte-identical. E, momentum and P blocks are unchanged.
- **The v5 reader is kept.** A v5 blob decodes to `slotCount = 1`, with slot 0 = (v5 species, v5 mass),
  slots 1..3 empty, and E, momentum and P copied verbatim.
  - By §4.9, `Σ_k m_k h_k(T) = m·h(T)` for one slot, so `T` is identical and **mass and energy are
    unchanged on load**.
  - UNIFORM sections stay UNIFORM (one slot).
- **The writer always emits v6.** A section that never gained a second species writes `slotCount = 1`: the
  same size as v5 plus one byte.
- **JNI.** `RegionMarshaller` widens the species/mass arrays to K planes. The migration gates are
  `ResidentLutParityIT`, `EnthalpyRestoreParityIT` and the v5 → v6 round-trip.
- **Legacy waterlogged blocks** loaded from v5 have no water component (their water was ignored). Load
  stays exact; what happens at their first wake is §12 Q4.

---

## 9. Design note: seawater (salt as a component), no implementation

What K-slot gives for free:
- `dissolved_salt` fits as the 4th slot in open seawater (water + salt + air + vapor = 4).
- Evaporation leaves salt behind: salt has no gas-side partner, so `χ_pair = 0` and terms (3)/P2 are 0 by
  data. Salinity rises.
- Precipitation to `halite` is a phase pair whose driver is solubility (≈ 360 g/L, CRC) instead of
  `p_sat`: the same P1/P2 shape.
- Brine density emerges from volume additivity (`ρ_p` of dissolved NaCl from its apparent molar volume,
  ≈ 17 cm³/mol).

What it does **not** solve:
1. **Solute rides solvent.** In term (2) a dissolved species gets its own `Φ_k` with `ρ̂_k ≫ ρ_water`, so
   it would sediment out of the water like sand. Seawater needs a solute–solvent coupling: one more LUT
   column (`solventIx`), so the dissolved species takes its slip potential and mobility from its solvent.
   That is a further law #8 change.
2. **Freezing-point depression** (seawater ≈ 271.2 K, UNESCO 1983 / TEOS-10) needs an activity term on the
   water's `T_data` (Raoult/Blagden). Not in this model.
3. **Saline soil** = sand + water + salt + air + vapor = 5 slots > K = 4. It needs K = 5 (+6 B/cell).

Verdict: the K-slot mixture is the right *carrier* for seawater. Seawater itself is a later amendment
(solvent coupling + activity), outside PRD #24.

---

## 10. Proposed DESIGN-LAW text (for the user to ratify; the agent does not edit the law)

**Replace #7 with:**
> 7. **State — store EXTENSIVE, derive INTENSIVE.** Each cell stores a **fixed composition of K slots**
>    (K = 4), each `(matIx_k, mass_k)`. An empty slot has `mass = 0`, and slots are unordered. The cell also
>    stores ONE `momentum (px,py,pz)`, ONE enthalpy `E` for the whole cell, and ONE `P`, plus persisted
>    **bookkeeping** with no physical meaning: `swapReady` and its partner key (the swap-cadence
>    accumulator) and `void_ix` (the engine free-list index). No other per-cell state, and no
>    variable-length per-cell storage.
>
>    Intensive quantities are **derived every tick, never stored**: `T` from `E = Σ_k m_k·h_k(T)` (one `T`
>    per cell); `velocity = momentum/Σ_k m_k`; the component volumes, the free/pore volume, the saturation
>    and the capillary pressure. A stored raw `v`, raw `T`, or stored saturation is drift, **forbidden**.
>    A cell with one occupied slot is exactly the one-material cell.

**Replace #8 with** today's text plus the following (the χ paragraph and the `τ_y` paragraph kept
verbatim):
> 8. **Material LUT = a fixed schema** (adding or removing a field is a law change): `heatCapacity,
>    thermalConductivity, molarMass, minMass, maxMass, viscosity, defaultMass, yieldStress, emissivity,
>    thermalExpansion, latentHeatMin, latentHeatMax`, the phase quadruple, a per-gas `T_ref`, **and
>    `particleDensity ρ_p` (default `defaultMass`), `flowResistance r` (= 1/permeability, default 0),
>    `airEntryPressure p_b` (default 0), `poreSizeIndex λ_BC` (default 1), `residualSaturation S_r`
>    (default 0)**. [χ paragraph verbatim.]
>
>    **The skeleton weight `s = r/(r + r_ε)`, the pair weight `χ_pair = max(χ_k, χ_partner)` and the gas
>    weight `γ_k = χ_k/max_j χ_j` are continuous weights in [0,1] of the same kind as χ. They are NEVER
>    compared to a threshold and NEVER used to classify.** `s` decides only where fluid seeps and whose
>    weight rests on contacts. Whether a component moves is decided only by `τ_y` (per component, per
>    face) and `viscosity`. [τ_y paragraph verbatim.]

**Replace #9 with:**
> 9. **Mass moves, never vanishes — per component.** Inside the domain each component's mass only *moves*,
>    by one of three paths:
>    - ONE antisymmetric per-component face flux: the yield-gated bulk share of the momentum flux, plus a
>      volume-zero-sum slip, plus vapor exchange. Each carries donor-budget clamps and per-component AND
>      shared-volume receiver-room clamps.
>    - A whole-cell permutation swap of all K slots.
>    - An in-cell phase transfer between phase-paired slots, which is the identity on `E`.
>
>    A species with no slot free in the receiver has zero room there: it is clamped, never dropped. The
>    only sources/sinks are the caller's place/break at the boundary and the per-slot `ε_mass` residue
>    (`m_k`, `m_k·h_k(T)`), both separately ledgered. A pushed cell with **no escape** is a **no-op**.
>
>    **Every** cell carries the same EOS pushback, Dalton's law: `p_eos = Σ_k γ_k·(m_k/M_k)·R·T/V_k^free −
>    P₀,cell`, entering `P` as `χ_cell·α_eos·(p_eos − P)` (for a one-slot cell `γ = 1`, `V^free = V`,
>    `χ_cell = χ`: today's term exactly). [Remainder of today's #9 χ = 0 paragraph verbatim.]
>
>    **Soft mass window (v4.4, INV-SOFTWIN), per component and per cell.** Each component keeps its own
>    walls `K·max(0, m_k − max_k)` and `K·(1−χ_k)·max(0, a_k·min_k − m_k)`, where `a_k` is the volume fraction
>    left to `k`. The cell adds a **volume wall** `K_V·max(0, Σ_k v_k − V)`: condensed matter never occupies
>    more than the cell.
>
>    A phase transfer ALWAYS happens once its threshold is crossed, keeping mass and `E`. If the partner
>    has no slot, `T` stays pinned at the threshold with `E` exact until one frees. [Remainder of today's
>    INV-SOFTWIN text verbatim, read per component.]

**Consequential one-line edits** (needed for consistency; the user may word them):
- **#2:** the hydrostatic density in the face terms is the intrinsic pore-fluid density
  `ρ_f = Σ(1−s_k)m_k/V_pore` on the pore-weighted area. A skeleton's weight rests on its contacts, not on
  `P`.
- **#4:** ENCODE also caches the volumes, saturation, capillary pressure, skeleton weights and lagged
  phase thresholds, and applies the Darcy drag (deposit → `E`). DECODE also performs the in-cell phase
  transfer and empties ε-slots. All of it is local.
- **#6:**
  - advection carries `Σ_k ṁ_k·h_k(T_donor)`;
  - a cell's conductivity and emissivity are the volume mixing rules of §4.8;
  - a pair's phase threshold is `(1−χ_pair)·T_data + χ_pair·T_sat(p_ref)`, lagged one tick, with `T_sat`
    from the pair's existing `L, cp, M, maxTemp` (no new column) and the Kirchhoff gap as the jump.
- **Drift test:** add (e) "a per-cell role flag (host/frame/pore/solvent) or a branch on one", and add the
  tokens `is_frame, is_host, is_pore, HOST_SLOT` to the build-failing list.

---

## 11. Slice-B tests this design commits to (preview; red-first, real LUT)

- `mmc_single_component_parity`: a K = 1 world is bit-identical to v4.4 for N ticks (vapor pairs at gauge
  0 only, per §4.9).
- `mmc_evaporation_into_air` (W1): vapor mass in A > 0 after N ticks; Σ H₂O and grand E + boundary exact.
- `mmc_dew_point_tracking`: cooled humid air condenses; `T` stays on `T_sat(p_v)` (± 0.1 K) while liquid
  appears.
- `mmc_infiltration_sand`, `mmc_infiltration_dirt` (W2): pore water rises at the real `K_s` order, air
  leaves upward, the dirt slot is untouched; per-species and E exact.
- `mmc_drainage_field_capacity` and `mmc_seepage_face`: the residual is in the Brooks–Corey band; drips
  into a cave only above the ghost-face suction.
- `mmc_saturated_rest`: saturated sand and waterlogged stairs stay put, with no slip after N ticks.
- `mmc_waterlogged_stairs_flow` (W3): water passes through; stairs mass unchanged; volume room respected
  with two simultaneous inflows.
- `mmc_rail_air_water_through` (W3).
- `mmc_slot_exhaustion` and `mmc_phase_slot_reservation`: a 5th species is clamped (room 0), never lost;
  freezing in a 3-species cell completes.
- `mmc_darcy_drag_energy`: KE removed by Darcy drag appears in `E` exactly.
- `SectionCodecV5MigrationTest`: v5 blob → v6, per-cell mass/E/T identical; v6 round-trip.
- `RepresentativeStateTest`: waterlogged hysteresis, host kept, host-absent fallback via
  `levelForFraction`, only vanilla states.
- Drift-test token scan extended per §10.

---

## 12. Open decisions for the user

> **RATIFIED 2026-10-22 by the user (session approval), recommended answers throughout.** The law text of
> §10 is applied in `DESIGN-LAW.md` (v5).
> - Q1: (c) the K-slot mixture cell, with the §10 law text.
> - Q2: K = 4.
> - Q3: boiling point follows pressure (`T_sat(P_atm + P)`); the stated parity deviation (deep hot water
>   boils later) is accepted.
> - Q4: (B) seed `ρ_water·V_pore` of water as a ledgered boundary placement on first simulation.
> - Q5: keep the host and hide the water (no deletion).
> - Q6: knob values as proposed (`g_vap` Penman f(u), `V_film = 1e-3 m³`, `τ_ph = 1 s`, `r_ε = 1e-6 m⁻²`,
>   `ε_p = 1 Pa`, `K_V` = the v4.4 wall `K`).
> - Q7: immediate rain-out accepted for Slice C; droplet-size mobility deferred.

1. **Ratify (c)** and the law text in §10 (#7/#8/#9 + the consequential #2/#4/#6 edits and drift-test (e))?
2. **K = 4 or K = 3?** K = 3 saves 6 B persisted and ~32 B transient per cell. It rules out water + air +
   vapor + one more (salt, or ice in wet soil), and the reservation rule then often holds the 3rd slot.
3. **Boiling point follows pressure** (`T_sat(P_atm + P)`)?
   - Accept the one stated parity deviation (deep hot water boils later), or
   - keep `T_data` for the liquid side (`p_ref = P_atm` for liquids), which gives exact parity and keeps
     `T_sat(p_v)` for the vapor side only (needed for humidity and dew).
4. **Legacy waterlogged blocks at their first wake?** Load itself stays exact either way.
   - (A) Leave them dry: the reconciler flips them to `waterlogged=false` once reconciled.
   - (B, recommended) Seed `ρ_water·V_pore` of water as a ledgered boundary placement the first time the
     chunk is simulated.
5. **Non-waterloggable host flooded** (rail/flower in a river)?
   - Keep the host and hide the water (recommended: no deletion), or
   - break the host vanilla-style past a flow threshold (a ledgered boundary removal + item drop).
6. **Knobs** to accept as numbers:
   - `g_vap` from Penman's wind function f(u) = 2.6(1+0.54u) mm·day⁻¹·kPa⁻¹, where `u` is the cell's wind
     speed;
   - `V_film = 1e-3 m³` (1 mm film); `τ_ph = 1 s`;
   - `r_ε = 1e-6 m⁻²`; `ε_p = 1 Pa`;
   - `K_V` (= the v4.4 wall `K` calibration).
7. **Cloud suspension.** Condensed liquid in an air cell rains out at once (CFL-capped), while real cloud
   droplets stay aloft. Accept for Slice C, or add a droplet-size mobility later?

---

## 13. Data and sources for the new columns (C5; transcribe and verify each value in Slice B)

| Column | Rows | Value / rule | Source |
|---|---|---|---|
| `ρ_p` | mineral soils (sand, dirt, coarse dirt, gravel, clay, mud) | 2650 kg/m³ | Blake & Hartge 1986, *Methods of Soil Analysis* Pt 1, ch. 14 |
| `ρ_p` | partial blocks (stairs, slabs, rails, plants, carpets…) | the substance density already used for `default_mass` (e.g. stone 2700, oak 750, iron 7870) | `docs/material-data-sources.md` (existing) |
| `r` (1/κ) | sand / loam / clay / gravel | κ from `K_s·μ/(ρg)`: sand 21 cm/h → 6e-12, loam 1.32 cm/h → 3.7e-13, clay 0.06 cm/h → 1.7e-14 m²; gravel ≈ 1e-9 m² | Rawls, Brakensiek & Saxton 1982, *Trans. ASAE* 25(5):1316, Table 2; Bear 1972 Table 5.5.1; Freeze & Cherry 1979 Table 2.2 |
| `r` | partial / flowable blocks | `12/(φ·h²)`, h = open gap (½ m stairs/slabs, 1 m rails/plants) | Hagen–Poiseuille slit, Bear 1972 §5.10 (derived, not measured: flag) |
| `p_b, λ_BC, S_r` | sand, loamy sand, loam, silt loam, clay … | e.g. sand 7.3 cm H₂O / 0.59 / θ_r 0.020; loam 11.2 cm / 0.22 / 0.027; clay 37.3 cm / 0.13 / 0.090 (`S_r = θ_r/φ`) | Rawls, Brakensiek & Saxton 1982, Table 2 (geometric means) |
| `g_vap` (knob) | global | Penman f(u) | Penman 1948, *Proc. R. Soc. A* 193:120; Shuttleworth 1993, *Handbook of Hydrology* ch. 4 |
| `p_sat(T)` check | water | — | Wagner & Pruß 2002 (IAPWS-95), *J. Phys. Chem. Ref. Data* 31:387 |
| mixing rules | — | geometric-mean k; drift-flux mixture; Brooks–Corey | Johansen 1975; Farouki 1981; Ishii 1975; Manninen et al. 1996 (VTT Publ. 288); Brooks & Corey 1964 (Hydrology Paper 3, Colorado State Univ.) |

`φ` (porosity) is **not** a column. It is derived as `1 − defaultMass/ρ_p`: sand 1600/2650 → 0.40 (real
range 0.35–0.45); dirt 1500/2650 → 0.43 (real 0.40–0.50); gravel 1800/2650 → 0.32 (real 0.25–0.40).

---

## 14. Rollout (after ratification)

| Step | Scope | Risk |
|---|---|---|
| 0 | User amends DESIGN-LAW #7/#8/#9 (+ #2/#4/#6, drift test) | doc |
| 1 | Engine: K-slot SoA + T sorting-network inverse + Dalton EOS + volume wall + shared room; parity tests (K = 1 bit-identical) | medium |
| 2 | Engine: per-component flux terms (1)(2), ψ gating, slot routing/reservation, Darcy drag + Brooks–Corey; W2/W3 tests | high |
| 3 | Engine: phase P1/P2/P3 + `T_sat` + lagged thresholds; W1/W4/W5 tests | medium |
| 4 | Java: v6 codec + v5 reader, JNI K planes, `RepresentativeState`, waterlogged seeding per Q4; vanilla-client join check | medium |
| 5 | Data: `ρ_p, r, p_b, λ_BC, S_r` rows + sources | low |

Step 1 alone must be a no-op on every existing world (parity), so it can land before the physics.
