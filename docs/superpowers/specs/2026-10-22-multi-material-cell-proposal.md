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

---

## 15. Slice B1 implementation record (issue #26)

Engine state, one-component equivalence and per-component conservation. Engine commit on
ORGE-ENGINE `issue/26` (merged into its `rebuild`), gitlink bumped in the ORGE PR that closes #26.

### 15.1 What landed

| Law v5 item | Where | Notes |
|---|---|---|
| #7 state: K = 4 `(matIx_k, mass_k)` slots, one `E`, one momentum, one `P` | `sim_engine.hpp` `Chunk::slotIx/slotMass` (slots 1..3), slot 0 = `matIx/mass_kg` | Fixed SoA planes, no per-cell allocation. Slot 0 keeps its historical name; no slot is special to the physics. Canonical storage order (sorted by species) is bookkeeping only. |
| #7 derived `T` from `E = Σ m_k·h_k(T)` | `orge_enthalpy::mix_T` / `mix_E` | Exact inverse of the mixture curve: one slope `C = Σ m_k cp_k`, one jump `m_k·L` per phase threshold, folded as `T ← min(T, max(t_j, T − J_j/C))` over the sorted breakpoints. A one-component cell reads `h⁻¹(E/m)` unchanged. |
| #8 five new LUT columns | `Material::particleDensity, flowResistance, airEntryPressure, poreSizeIndex, residualSaturation` | Appended with the law defaults (`ρ_p = defaultMass` via `particle_density()`). Not yet consumed (slip / Darcy / Brooks–Corey are B4/B5) and not yet sent over JNI registration (the data rows land with those slices). |
| #9 ONE per-component face flux, bulk term | `resolve_world` R2 commit, `CellAccum::dmk` | `f_k = y_k·ṁ` with the donor's mass fractions; one float per component written ± to both sides, so per-species mass is antisymmetric exactly like grand mass. |
| #9 whole-cell swap of all K slots | `mix_end` (swap branch) | The parcel's slots arrive verbatim from the partner's pre-step composition. |
| #9 in-cell phase transfer, identity on `E` | `mix_end` | A component whose band the cell's one `T` has left relabels in place to its phase target. `E` is never touched (ΔE ≡ 0); chain anchoring keeps `T` continuous. |
| #9 ε-slot residue | `mix_end`, `World::boundaryMassEps` | `m_k < ε_mass` leaves with `m_k·h_k(T)` to `World::boundaryE`. |
| #9 Dalton EOS + `χ_cell` | `mix_material` | `p_abs = Σ γ_k n_k R T / V_free,k`, mole-weighted `P₀,cell`, realized exactly through the mixture row's effective molar mass and reference temperature, so the unchanged `p_eos_v4` / stiffness / anchor read the law value. `χ_cell = chi(mixture row)`. |
| #4 locality | `snapshot_world_mmc` (ENCODE side), `mix_end` (DECODE side) | Both per-cell; `mix_end`'s swap branch reads the 1-hop partner exactly like DECODE's swap. |
| JNI | `orgeStepWorld` | 4 arrays appended: slots 1..3 species + mass in/out, `((c·3) + (k−1))·CHUNK_N + i`. Null or short = one-material cells. `tout` reads the mixture `T` for a multi-component cell. Java: `ColumnTask/ColumnResult.slotIx/slotMass`, `RegionMarshaller.SLOT_N`, `EngineMarshaller.Kernel`. |

**How a multi-component cell runs the same kernels.** It enters the snapshot with its total mass
`M = Σ m_k`, velocity `p/M`, its own per-cell mixture material (`cell_mat`), and a per-step
composition label (one LUT row per distinct species set) as its species identity. Every kernel reads
`cell_mat()`; a one-material cell reads its LUT row, so it is bit-identical. Same-label faces flux
(bulk term), a vacuum receiver adopts the label, different labels are no-flux and exchange by the
whole-cell swap: today's "cross-species moves only by swap" with *species* generalized to *species set*.

### 15.2 Memory and kernels (C2)

| | today | B1 (K = 4) |
|---|---|---|
| persisted B/cell | 38 | **56** (+18: 3 × (u16 + f32)) |
| transient B/cell, every step | ~37 | +32 (`CellAccum::dmk`, 4 × f64) |
| transient, multi-component cells only | — | `mixRow` 4 B/cell in a chunk that holds one, + per such cell `MixComp` 52 B + `Material` row ~88 B |

**Kernel count delta: 0.** It stays `6 + 2·N_relax`: the composition snapshot rides ENCODE, the bulk
component shares ride R2's existing commit, the composition pass rides DECODE. A World with no
multi-component cell takes exactly today's path (`MmcPlan.active == false`).

### 15.3 Evidence

`ORGE-ENGINE/tests/engine_b_mmc_state_test.cpp` (cheap tier), real LUT:
(A) a one-material scene forced through the multi-component path is bit-identical for 40 ticks;
(B) a multi-component cell elsewhere leaves a one-material region bit-identical;
(C) 56 B persisted per cell, canonical slots;
(D) `T` derived from `E` on the mixture curve, a scribbled `T` never survives, a component's band pins `T`;
(E) a water + air body falling and spreading through a sealed box for 60 ticks: grand mass, H₂O and air
(Σ over every slot + ε ledger) and grand energy exact every tick, moved mass carries the donor's composition;
(F) a moving two-component cell pours into vacuum along all 6 directions with identical moved mass,
composition and carried momentum;
(G) per-component boil, ΔE ≡ 0, `T` continuous; (H) swap carries all slots; (I) ε-slot ledger;
(J) placement over a multi-component incumbent ledgers every component.
`engine_b_inv_universal_test` scans for the drift-test (e) tokens.

### 15.4 Deliberately not in B1

Slip (term 2), vapor exchange (term 3), the P1/P2 partial transfer at `T_sat`, Darcy drag,
Brooks–Corey retention, slot routing between *different* species sets (§4.6) and the per-component
walls beyond the mixture window: B3 (evaporation/condensation), B4 (infiltration), B5 (flow through
blocks). Persistence (v6 codec + v5 reader) is B2. The placement path ledgers a multi-component
incumbent instead of relocating it.

---

## 16. Slice B4 implementation record (issue #29)

Infiltration of water into dirt/sand as pore water, and drainage back out. Engine commit on ORGE-ENGINE
`issue/29` (merged into its `rebuild`), gitlink bumped in the ORGE PR that closes #29.

### 16.1 What landed

| Law v5 item | Where | Notes |
|---|---|---|
| #9 per-component face flux, slip term (2), liquid part | `engine_b.hpp` `seep_world` (RESOLVE micro-passes S1 / S1.5 / S2) + `seep_apply` (DECODE side) | Darcy flux of each condensed component down its potential `Φ = P − (1−χ)·p_c + ρ·g·y`, upwind Brooks–Corey `k_r`, face resistance `½(r_cell,i + r_cell,j) + r_ε`, pore-weighted area `A_f`, law #8 yield gate `ψ` per component per face. Weighted by the skeleton weight `w_s = max(s_i, s_j)` — `s` decides only where fluid seeps (law #8). A `μ = +INF` skeleton has mobility 0 by arithmetic and never moves. |
| #9 boundary face | `seep_intent` | The receiver's potential blends continuously into law #2's half-cell ghost by its own liquid weight `w = clamp(v/V_film, 0, 1)`: a dry receiver (cave, dry soil) is a seepage face — saturated soil drips only while `p_c < ½·ρ_w·g·Δx`. |
| #9 donor budget, per component | S1 | `σ_k = min(1, budget_k/Σ_faces f_k)`, budget = the component's mass after this tick's bulk flux (the bulk flux spends first). |
| #9 receiver room, per component AND shared volume | S1.5 | `room_k = max_k − m_k` (after bulk inflow) and the volume wall `V − Σ v_k`; scale `ρ'_k·((1−χ_k)·ρ'_V + χ_k)`. Pore water is capped at the pore volume (sand 396 kg, loam 434 kg) by the wall, with no "pore capacity" rule. |
| §4.6 slot routing | S1.5 | One new slot granted per receiver per tick to the largest inbound species (tie → lower matIx), never the last free slot while a resident component's phase partner is absent. A species without a slot has room 0: clamped, never dropped. |
| #6 advection | S2 | The seeping mass carries `f·h_k(T_donor)` and `f·u_donor`; every face writes the same double ± to both sides. |
| #4 locality | `seep_world` / `seep_apply` | Every pass reads only its own cell and the 6 face neighbours; each cell sums its own six faces (order-independent). `seep_apply` touches only its own cell's accumulators. Kernel count: the three micro-passes ride RESOLVE, the apply rides DECODE. |
| #8 data | `docs/material-data-sources.md` "Soil water"; `materials/*.json`; `Material` / `MaterialCodec` / `LutArrays` / `NativeEngine` / `orge_jni.cpp` | The five v5 columns now cross the JNI registration. Loam (dirt family) and sand rows carry real ρ_p, κ, Brooks–Corey `p_b, λ, S_r` (Rawls, Brakensiek & Saxton 1982; Blake & Hartge 1986). |

### 16.2 Choices made in this slice (documented, not law changes)

- **Brooks–Corey suction cap.** `p_c = min(1.5 MPa, p_b·(S_e + ε_S)^(−1/λ))`: Brooks–Corey diverges at
  `S_e = 0`; the cap is the permanent wilting point (FAO-56, −1500 kPa), the dry end of real soils.
- **Skeleton pore pressure.** A skeleton cell is closed to the bulk flux and carries no relaxed `P`, so
  its pore liquid's potential is its suction alone (no positive pore pressure in soil yet).
- **Gas slip deferred.** A gas component is weighted out by `(1−χ)`; the counter-current air slip of
  term (2) (§4.5's volume-zero-sum projection) is not in this slice — today's soil pores hold no air
  component, so water enters empty pore volume. Lands with B5 (flow through blocks).
- **Faces skipped exactly.** A LUT with no `r > 0` row, and any face whose weight
  `max(s_i, s_j)·(mob_i + mob_j)` is exactly zero, carries an exact zero and is not visited.
- **ε rule after seepage.** DECODE's ε / vacuum rules run before the seepage is applied, so `seep_apply`
  applies the same rule to the post-seepage composition: a sub-ε component leaves to the ledger with
  `m_k·h_k(T)`, a cell drained below ε empties whole (its E to `boundaryE`). A seepage intent below
  `ε_mass` is 0 and a slot is granted only to an inbound amount of at least `ε_mass` (the bulk flux's
  value floor), so a gas's `(1−χ)`-weighted trickle never opens a slot.
- **No wicking into bone-dry soil.** A receiver with no liquid of `k` reads the half-cell ghost
  (§4.5 boundary face), not its own suction, so unsaturated soil does not wick into bone-dry soil beside
  it; it does once the receiver holds a film. Ponded water and saturated soil still enter dry soil.

### 16.3 Evidence

`ORGE-ENGINE/tests/engine_b_mmc_infiltration_test.cpp` (cheap tier), real LUT + `add_real_soils`:
(A) a water layer on dry sand: pore water rises, free water falls by the same mass, the sand component
untouched in every cell, grand / per-species (H₂O over every slot + ε ledger, sand) mass and grand energy
exact every tick; (B) the same on loam, slower than sand (real `K_s`); (C) saturated sand over an empty
cave drains into it; (D) saturated sand over dry sand drains into it; (E) a nearly saturated sand layer
under a 6 m pond fills to its pore volume and no further (the volume wall); (F) pore water that freezes
in the tick it drains is debited from its ice; (G) a fully drained film leaves no sub-ε residue, orphan
E or non-physical T; (H) air on dry sand opens no trickle slot. RED on the base engine (11 failures),
GREEN with the change; (G)/(H) were found by an adversarial review and are RED before its fix. Java: `LutPackTest`, `MaterialCodecTest`, `AllMaterialsLoadTest`
(`soilsCarryRealPorousMediumData`).

---

## 17. Slice B3 implementation record (issue #28)

Evaporation of water into an air cell and condensation back. Engine commit on ORGE-ENGINE `issue/28`
(merged into its `rebuild`), gitlink bumped in the ORGE PR that closes #28.

### 17.1 What landed

| Law v5 item | Where | Notes |
|---|---|---|
| #6 phase threshold `(1−χ_pair)·T_data + χ_pair·T_sat(p_ref)` with the Kirchhoff gap | `sim_engine.hpp` `orge_enthalpy::mix_bands` / `mix_T` | `p_ref = χ_k·p_k + (1−χ_k)·(P_atm + P)`, `p_k` the component's Dalton partial pressure. The inverse reads the thresholds at the lagged T, then once more at the T it gives. A χ_pair = 0 pair (ice↔water, stone↔lava) is bit-identical to B1 (`G = L` exactly). |
| §4.7 saturation curve from existing columns | `orge_enthalpy::phase_pair`, `p_sat`, `T_sat` | Clausius–Clapeyron + Kirchhoff, inverse = constant-L closed form + 2 Newton steps; finite for every pair (NaN-safe clamps), `p_sat(T_b) = P_atm` and `T_sat(P_atm) = T_b` exactly. |
| #9 path 3 / §4.7 P1: in-cell phase transfer at the moving threshold | `engine_b.hpp` `mix_end` | The share of a band the cell's E has paid moves to the phase partner (lever rule), capped by a fixed 12-step bisection at the largest share that leaves the component on its own re-read threshold — a vapor's dew point falls as it condenses, so the lagged lever share would over-condense. ΔE ≡ 0 (E is not touched). Shares are floored to the slots' mass lattice so both float masses change exactly. No slot for the partner: the band stays open (T pinned, E exact) until the whole component relabels in place. |
| #9 face flux term (3), vapor exchange (§4.7 P3) | `engine_b.hpp` `vap_world` (RESOLVE micro-passes V1 / V1.5 / V2) + `vap_apply` (DECODE) | Per face, two liquid\|gas interfaces, `e(a,b) = w_liq,a·w_gas,b·(p_sat,a − p_v,b)`: evaporation (liquid c leaves a, lands as vapor h in b) or condensation (vapor leaves b, lands on a's liquid), `F = χ_pair·g_vap·A·dt·|e|`. Every piece carries `h_hot(T_donor)`: the donor pays the latent heat, a vapor landing on liquid releases it. Donor budget above the component's cohesive floor `(1−χ_k)·a_k·min_k` after the bulk flux and seepage; per-species + shared-volume receiver room; one slot grant per tick with the §4.6 reservation, after seepage's grant. |
| #9 exactness at float32 storage | `vap_quantize`, `mass_lattice` | A tick's exchange (~1e-5 kg) is below the float32 spacing of a full cell (1000 kg: 6.1e-5 kg). Every committed piece is a multiple of the face's mass lattice (a power of two ≥ the float spacing of every slot value either cell takes this tick), so both float masses change exactly; the sub-lattice remainder is dithered by a stateless hash of (donor, receiver, pair, species, tick) — unbiased, deterministic, identical on both sides. |
| §12 Q6 knob `g_vap` | `G_VAP_PENMAN` = 3.0e-8 kg·m⁻²·s⁻¹·Pa⁻¹; `Globals::g_vap` default **0** | Penman's still-air f(u=0); `docs/material-data-sources.md` "Evaporation and condensation". 0 switches the exchange off exactly. The engine default stays 0 until #41 (see 17.2); the tests run the Penman value. |

### 17.2 Choices made in this slice (documented, not law changes)

- **The exchange is off by default (`g_vap = 0`) until #41.** Vapor makes an air cell the species set
  `{air, steam}`. B1 lets a species set take part in neither the bulk flux with a *different* set nor the
  §6.3 displacement, so with the exchange on, water stops leveling through the humid air it creates
  (`engine_b_horizontal_flow_test` LEVEL2-AIR: 4 cells instead of 9) and a sealed air pocket under water
  loses its rest (`engine_b_gas_test` INV-GAS, INV-AL). Shipping that would stop water flowing beside every
  lake, so the knob ships at 0 and the physics is verified at the Penman value in its own test. Turning
  it on is one line once §4.6 routing reaches the bulk flux and the displacement (#41, with B5 #30).
- **Interface weights instead of `p*` mixing.** §4.7 writes the face drive as `p*_i − p*_j` with
  `p* = w_liq·p_sat + (1−w_liq)·p_v`. Between a liquid cell and a gas cell this is the same value; but
  between two cells that both hold liquid and no gas it drives a heat-pipe exchange through a liquid, and
  it lands vapor in a cell with no gas to carry it. The slice uses the two liquid\|gas interfaces of the
  face instead (the liquid of one side against the gas of the other, gas weight `w_gas` = the cell's moles
  of gas over a 1 mm film of gas at P_atm). No liquid on one side or no gas on the other is an exact 0:
  water beside vacuum or stone does not evaporate.
- **P2 (in-cell sub-saturation evaporation) is not in this slice.** Liquid that condensed inside an air cell
  (fog) re-evaporates only across a face into drier gas. Lands with Slice C3 (daytime evaporation).
- **Gas↔gas vapor diffusion is not in this slice.** Humid air shares its vapor with dry air through the
  bulk flux and the swap; different species sets do not bulk-flux yet (B1 §15.4), so a humid cell over
  water saturates in place.
- **One-material cells keep `T_data`.** A pure water cell still boils at 373 K and a pure steam cell
  condenses there (the one-material curve `T_of_eta`); the moving threshold applies to composition cells
  (the mixture curve). Boiling-point elevation in a pure deep-water cell (§12 Q3) is follow-up work.
- **Humid air is its own species set.** Until the bulk term routes between different sets (§4.6, with B5), a
  humid cell exchanges with dry air only by the swap; composition-label numbering can also couple
  tie-breaks across a World. Both filed as #41 (with P2 and one-material boiling-point elevation).
- **A full receiver takes no condensate.** A water cell at `maxMass` has no room; condensation lands on a
  water cell below its max (or condenses in the air cell itself once it reaches the dew point).

### 17.3 Evidence

`ORGE-ENGINE/tests/engine_b_mmc_vapor_test.cpp` (cheap tier), real LUT: (A) a 300 K water cell under dry air
evaporates 3.66e-3 kg in 30 s (Penman 3.35e-3), the water loses exactly what the air gains, the vapor carries
m·h_steam(T_water) (latent included) and the water is cooler than its g_vap = 0 twin; (B) humid air at rest
above its dew point keeps its vapor; cooled through a ledgered sink it condenses 0.010 kg in place, T rides
T_sat(p_v) within 0.004 K and ends 20 K above the sensible-only path; (C) humid air over 280 K water
condenses 1.2e-3 kg onto it, which gains exactly the vapor and its m·h_steam; (D) water beside vacuum does
not evaporate; (E) the saturation curve vs IAPWS-95; (F) humid air already holding four species cannot
open a slot for its condensate: T stays pinned on the dew point for 34 ticks with E exact, then the whole
vapor relabels in place; (G) water over dry sand with pore air seeps and evaporates into the same cell in
the same tick (both slot grants). Grand mass, H₂O and air exact to the bit (G: to the f32 storage standard,
seepage rounds), grand energy to 1e-5 J every tick. RED on the base engine: no vapor ever appears over
water, and humid air at 300 K condenses at once and jumps to 336.7 K. `rlut::grand_mass` /
`species_mass` now sum every slot (law v5 #7). With the engine default (`g_vap = 0`) every other cheap-tier
test is unchanged (same failing checks as the base, none new).

---

## 18. Slice B5 implementation record (issue #30)

Flow through waterlogged, flowable and permeable blocks, and the representative vanilla state. Engine
commit on ORGE-ENGINE `issue/30` (merged into its `rebuild`), gitlink bumped in the ORGE PR that closes #30.

### 18.1 What landed

| Law v5 item | Where | Notes |
|---|---|---|
| #8 data (W3) | `materials/*_stairs`, `*_slab`, rails, herbaceous plants, coral ×30; `docs/material-data-sources.md` "Partial, flowable and waterlogged blocks" | `ρ_p` = the block's substance, `r = 12/(φ·h²)` (§13). Stairs/slabs/rails/plants/coral are skeletons (`s ≈ 1`) with open volume; the B4 seepage carries water through them, the host never moves (τ_y / μ). |
| #9 slip term (2), gas half | `seep_intent` | A component's pressure is `(1−χ_k)·(P − p_c) + χ_k·p_eos,cell` (the cell's Dalton gauge EOS): a condensed component reads exactly B4's value; a gas reads the EOS, the only thing that bounds it in a skeleton cell (no relaxed `P` there). The `(1−χ)` weight on the flux is gone, so air slips through a rail like water. A gas flux never carries more than half the mass that would equalize the two sides' EOS (`½·ΔΦ/(∂p/∂m_d + ∂p/∂m_r)`, `∂p/∂m = χ·R·T/(M·V_free)` — 0 for a condensed component, so B4 is unchanged). The presence weight `w_k = clamp(m_k/(ρ_p,k·V_film), 0, 1)` now counts a gas too. |
| #2 pore-fluid density | `pore_fluid_rho` (P relaxation) | The relaxation's face density is `ρ_f = Σ(1−s_k)·m_k / V_pore`, `V_pore = V − Σ s_k·v_k` (law #2 v5): a skeleton's weight rests on its contacts. Exactly `m/V` with no skeleton component. Without it a finite-μ host (stone stairs + water) relaxed `P` with 2275 kg/m³ and pumped its pore water out sideways. |
| #9 donor budget | `seep_world` S1 | The post-bulk budget is the float mass DECODE / `mix_end` actually write (and 0 when DECODE's vacuum guard empties the cell). The double-precision budget could disagree: seepage debited a component DECODE had already emptied — an assert in debug, **mass created** in the `-DNDEBUG` `liborge.so`. Pre-existing since B4; first reached by a cave fed through a skeleton. |
| #9 exact per-species books | `seep_commit` | A committed seepage piece is floored to the face's mass lattice `q = max(q_d, q_r)` (B3's `mass_lattice`), so both float masses change exactly — now that air slips, B3's bit-exact air books must hold through seepage too. A donor that spends its whole budget (`σ < 1`) is not floored: it drains to the ε rule instead of keeping a sub-lattice film (a thin water residue blew up a draining pond). |
| §6 representative state | Java `RepresentativeState` + `MinecraftFluidReconciler` | Slots come back in LUT (alphabetical id) order, so `orge:air`/`orge:water` can be slot 0 of a rail/soil cell. The reconciler now reads the cell's whole composition: a non-fluid live block still among the components is KEPT and only its `waterlogged` follows the water (`v_w/V_pore`, on ≥ 0.50, off < 0.25); a host-absent composition is drawn as its largest condensed component through the existing level path. One component is today's path. |
| §12 Q4 (B) | Java `ColumnAssembler` / `ColumnSnapshot`, `SectionData`/`SectionCodec` legacy mark | A `waterlogged=true` block gets `ρ_w·V_pore` of water as a second component when its section is first simulated, or once for a section decoded from v5. A simulated cell whose water drained is never re-seeded. |

### 18.2 Choices made in this slice (documented, not law changes)

- **Gas potential = the EOS.** A skeleton cell carries no relaxed `P` (B4 §16.2), so a gas there is bounded
  only by its EOS; using the χ-blend everywhere keeps both sides of a face on one reference.
- **Air now enters soil pores** (W3 "air through all of them") only where the drive is large: sand/loam `r`
  makes the flux smaller than one lattice step for an ordinary gauge difference. Infiltration (H) is
  re-authored accordingly (no sub-ε air slot, EOS-bounded), as §16.2 scheduled.
- **Waterloggable blocks with no open volume** (fences, walls, lanterns, signs, leaves…) keep their rows
  (no `ρ_p`): their water is still ignored and their `waterlogged` property is left as vanilla set it.
- **The seed is the initial condition**, like ambient air and ocean water: it is part of the engine's
  input mass, not a mid-run boundary placement.
- **Counter-current projection** (§4.5 `q'`) is not built: air leaves a filling pore by its own EOS rise.

### 18.3 Evidence

`ORGE-ENGINE/tests/engine_b_mmc_flow_through_test.cpp` (cheap tier), real LUT + `add_real_partial_blocks`:
(A) water passes through waterlogged stairs into a cave; (B) dry stairs under a pond with side inflows fill
to their 250 kg open volume and no further; (C) water through rails; (D) air through a wall of rails into
resting air, a rail cell never pumped past the EOS; (E) water through short grass; (F) a waterlogged stair
in still water keeps its water and the water around it stays still (no pump). Every scene: host mass
bit-exact per cell, grand / per-species mass and grand energy exact every tick. On the base engine the
test aborts in (A) (the budget bug) and (B)/(D) fail. Java: `RepresentativeStateTest`,
`ColumnAssemblerTest.waterloggedBlockCarriesItsWaterOnceAndADrainedOneIsNeverReseeded`,
`SectionCodecTest.v5SectionsAreMarkedForTheOneTimeWaterloggedSeed`,
`AllMaterialsLoadTest.partialAndFlowableBlocksCarryTheirOpenVolume`.

---

## 19. Slice B6 acceptance record (issue #31)

End-to-end acceptance of the multi-material cell on a real dedicated server: a vanilla 1.21.11 client
joins, and a pre-change (v5, one-material) world loads and migrates. Engine commit on ORGE-ENGINE
`issue/31` (merged into its `rebuild`), gitlink bumped and `liborge.so` rebuilt in the ORGE PR that
closes #31.

### 19.1 What landed

| Item | Where | Notes |
|---|---|---|
| C4 migration books | Java `RegionStore.MigrationAudit`, `ColumnTotals`, `SectionCodec.readColumn(blob, wire)`, `SectionStoreManager` | Every v5 column the store loads is booked twice: **before** = grand mass, grand `E` and per-species mass exactly as the v5 bytes state them (summed from the wire payload before `SectionData` adopts it), **after** = the same sums over the migrated cells, every slot of every cell. Sums run cell by cell in double, so equality is exact, not a tolerance. On every level save (and unload) that migrated new columns the server logs one line per dimension: `[ORGE] <dim> v5 -> v6 migration: N columns; before [...]; after [...]; IDENTICAL` (WARN and `MISMATCH` if not). Cost: one pass over the cells of a column the first time it loads. |
| C1 server liveness | ORGE-ENGINE `jni/orge_jni.cpp` `orgeStepWorld` | The arrays now cross the JNI seam by copy (`Get/Set<T>ArrayRegion`). Before, `GetPrimitiveArrayCritical` held every array for the whole native step, which locks out the garbage collector: the server thread parked at its first allocation that needed a GC until the step ended. Observed live: 171 s frozen, and the vanilla client dropped with `client timed out after 30000 milliseconds`. Transient cost: one native copy of the step's input and output arrays (they were already copied into the engine `World`). `orgeRegisterMaterials` (a short LUT copy) is unchanged. |
| Acceptance tooling | `tools/acceptance/` | `vanilla_join.js` (the vanilla client, scene, checks), `orge_totals.py` (an ORGE-free reader of `world/orge` region files, v5 and v6: totals and per-cell composition), `rcon.js`. |

### 19.2 Memory per cell and kernel count (C2), cross-checked against #26 (§15.2)

Measured on ORGE-ENGINE `1914a9d` with `sizeof` on the engine structs (g++ 15.2, x86-64).

| | B1 record (§15.2) | now (after B3, B4, B5, #44) |
|---|---|---|
| persisted B/cell (`Chunk`: `matIx` u16, `mass` f32, `px,py,pz` f32, `E` f64, `P` f32, `swapReady` f32, `swapPartnerKey` i32, 3 × (`slotIx` u16 + `slotMass` f32)) | 56 | **56** (unchanged; `engine_b_mmc_state_test` (C) asserts it) |
| derived per tick (`T_curr`, `T_next`) | 8 | 8 |
| transient, every cell every step: `CellEncrypt` | 32 | 32 |
| transient, every cell every step: `CellAccum` (incl. `dmk`, 4 × f64) | 96 | 96 |
| transient, every cell: seepage weight planes (`s`, `mob`, 2 × f64; present whenever the LUT has a skeleton row — always with the real LUT) | — | **16** |
| transient, per composition cell: `MixComp` + `Material` row | 52 + ~88 | 52 + ~88 |
| transient, per seepage-involved cell: `SeepCell` + `SeepOut` | — | **384 + 280** |
| transient, per vapor-involved cell (only when `g_vap > 0`; on by default since #41, `g_vap = G_VAP_PENMAN`): `VapCell` + `SeepOut` | — | **1016 + 280** |
| transient, every cell, rest freeze on (the live JNI step, #73/#79): `RestCell` + frozen mask | — | **52 + 1** |
| on-disk v6 section, raw before deflate (`E` f32, mass f32, `matIx` u16, momentum 3 × f32, `P` f32, slots 3 × (u16 + f32)) | — | 44 B/cell; a one-component section writes no composition block (26 B/cell raw) |

No per-cell variable-length storage anywhere: the involved-cell lists are per-step scratch.

**Kernel count.** B1 kept `6 + 2·N_relax` (ENCODE, R0, R1, R1.5, R2, DECODE + the relaxation half-sweeps)
and that held for B1. B4 and B3 each added three RESOLVE micro-passes that need a grid-wide barrier
between them on a GPU, because each reads its neighbours' result of the one before: seepage S1 (donor
scale) → S1.5 (receiver scale, reads 1-hop σ) → S2 (commit), and vapor V1 → V1.5 → V2. Their per-cell
derivations fold into R2's tail (own cell + own accumulator) and the apply steps into DECODE. So the
count is now **`12 + 2·N_relax`** with both active, **`9 + 2·N_relax`** as shipped (vapor exchange off
at `g_vap = 0`; seepage always runs with the real LUT). §16.1's "the three micro-passes ride RESOLVE"
and §17.1's "RESOLVE micro-passes" are these six. `freeze_evict_world` (v4 §8.4, pre-v5) is a further
transactional pass between RESOLVE and DECODE that the `6 + 2·N_relax` formula never counted; it is
unchanged here.

**Kernel count as shipped (updated by #79).** #41 turned the vapor exchange on by default (`engine_b.hpp`
`Globals::g_vap = G_VAP_PENMAN`), so V1 → V1.5 → V2 run in every shipped step: the count is
**`12 + 2·N_relax`** with the rest freeze off. #73's rest freeze (`Globals::rest_freeze`, on in the live JNI
step, `jni/orge_step_world.hpp`) adds two grid-wide passes before ENCODE that a GPU needs a barrier between:
`rest_cells` (each cell's own rest descriptor: |u|, every component's mass window and phase band, its bulk
mobility, its in-cell exchange weight, its composition) and the face sweep of `mark_rest_world` (each cell
reads its six neighbours' descriptors and clears its frozen bit when a face is not at rest; on the CPU the
in-chunk faces and the cross-chunk faces are two loops of this one pass, and a face only clears, so they
need no barrier between them). Parking the fully frozen chunks and relabelling the frozen cells to the rest
row ride the snapshot (ENCODE). So the **live JNI step runs `14 + 2·N_relax`** kernels: ENCODE, the
`2·N_relax` relaxation half-sweeps, R0, R1, R1.5, R2, S1, S1.5, S2, V1, V1.5, V2, DECODE, plus the two
rest-freeze passes; `freeze_evict_world` stays outside the formula as above.

### 19.3 Acceptance procedure and evidence (C1, C4)

**Server.** Fabric 1.21.11 dedicated server (Fabric loader 0.19.2, fabric-api 0.141.4, architectury
19.0.1, this build's `orge-fabric-1.21-2.0.0+1.21.11.jar`; startup banner
`parent 4459b36 | engine 1914a9d`), `online-mode=false`, superflat, one player.

**Pre-change world.** A real world saved by build `52f902e` (#26, before the v6 codec): 25 columns in four
region files, every one format v5 (checked by `orge_totals.py`). Each run starts from a fresh copy of it.

**C4 — migration.** `orge_totals.py` on the untouched copy, an ORGE-free reader of the bytes:
`columns 25: mass 49453713.716496 kg, E 17092631376707.332031 J` (air 2914713.716496, bedrock 18432000,
dirt 18433500, grass_block 9214500, stone 459000 kg). The server, after force-loading the 25 columns and
`save-all flush`:

```
[ORGE] minecraft:overworld v5 -> v6 migration: 25 columns; before [mass 49453713.716496 kg, E 17092631376707.309000 J,
species {orge:air=2914713.716496, orge:bedrock=18432000.000000, orge:dirt=18433500.000000, orge:grass_block=9214500.000000,
orge:stone=459000.000000}]; after [mass 49453713.716496 kg, E 17092631376707.309000 J, species {...same...}]; IDENTICAL
```

Before and after are bit-identical. The independent reader agrees on mass to the printed µg and on `E` to
0.023 J of 1.7·10¹³ J (relative 1.3·10⁻¹⁵: it sums in a different order). Runs on the pre-fix and on the
final build printed the same line for these 25 columns.

**C1 — vanilla client.** `tools/acceptance/vanilla_join.js`: mineflayer 4.39 / minecraft-protocol 1.68,
protocol 774 (1.21.11), offline auth. It speaks only the vanilla protocol and decodes only vanilla
registries and block states, so it is an unmodded client. It builds the scene over RCON, one edit per
cell: three waterlogged oak stairs and a waterlogged oak slab beside a water cell, a sand bed and a dirt
bed under ponded water, and a water pool. Then the bot walks and swims a loop through it. The final run
used build `4459b36` (rebased on #33 rain and #34 daytime evaporation): the bot stayed **connected for 25
minutes** (10:34:56 → 11:00:01) and reached 1115 waypoints. The script recorded:

- custom payloads received: `configuration minecraft:register` ×1, `configuration minecraft:brand` ×1
  (both from the server stack; there are no ORGE channels);
- 23 registries, **no non-`minecraft:` registry id or entry**. Tag names include the `c:` convention
  namespace (fabric-api's convention tags); a vanilla client accepts unknown tag names;
- every block state the client held for the 1785 scene blocks decoded to a vanilla state: **0 unknown**,
  at all three checkpoints. The client also received ORGE's representative-state updates: the stairs
  went `waterlogged=true → false` as their pore water drained, and a `water[level=4]` appeared;
- no kick, no protocol error, no disconnect until the script quit.

What the engine did meanwhile, read from the saved world with `orge_totals.py`:

| cell | composition at the end |
|---|---|
| sand under ponded water | `orge:sand 1600 kg + orge:water 33.15 kg` (pore water: wet sand) |
| dirt under ponded water | `orge:dirt 1500 kg + orge:water 0.83 kg` (wet dirt) |
| air over the ponds and the pool | `orge:air 1.2 kg + orge:steam 0.0020–0.0025 kg` (evaporated water) |
| waterlogged slab | `orge:oak_slab 375 kg + orge:water 499.9 kg` |
| waterlogged stair | `orge:oak_stairs 562.5 kg + orge:air 0.30 kg` (its water drained; client sees `waterlogged=false`) |

**Server liveness before the JNI fix (§19.1).** The first runs used the pre-fix `liborge.so`. A step
over force-loaded columns parked the server thread for 15+ minutes (thread dump: `Server thread` "waiting
on condition" in an allocation while `orge-conduction` ran `orgeStepWorld`). In a join run the server
reported `Running 171332ms or 3426 ticks behind`, and the client dropped with
`client timed out after 30000 milliseconds`. `JniStepGcIT` reproduces it: a whole heap of allocations on
another thread finishes after the native call (19.18 s vs 12.62 s) with the old library and well before it
(9.05 s vs 18.26 s) with the fix.

**Tick rate.** The final run lowered the game to `/tick rate 1`. On this shared host (64 cores, load
average 55–80) one native step at `range 1` took 32–34 s, and the scheduler cancels a step after 40 game
ticks. At 20 TPS every step was cancelled (`0 completed, 16 cancelled`) and the scene never evolved.
The vanilla client handles tick-rate changes. Filed as #51 (19.4).

**Reproduce:** set up a Fabric 1.21.11 server with `enable-rcon=true`. Copy a pre-change world, delete
`world/playerdata/*`, and start the server. Then, in `tools/acceptance`, run `npm ci`,
`ORGE_RCON_PASSWORD=<rcon.password> node vanilla_join.js 127.0.0.1 <port> <rconPort> 600 1`, and
`python3 orge_totals.py <world>/orge --cell=x,y,z …`.

### 19.4 Found here, filed separately (not fixed in B6)

- #50: `/orge get` reads slot 0 only, so a composition cell shows the wrong mass and `T` (a drained stair
  printed `6000 K, 0.0 kg, material=orge:air`). Display only: the state is right.
- #51: live performance: at 20 TPS no step completes on a loaded host (native 32–34 s, ORGE server-thread
  work 7.5–12.8 s per cycle, 40-tick grace).
- #52: ORGE-ENGINE cheap tier at `fbbb7fe`: 74 PASS, 7 FAIL, all before #31 (#31's engine change is in
  `jni/orge_jni.cpp`, which no cheap test compiles).

---

## 20. Engine debt from Slice B3 record (issue #41)

Humid air moving with dry air, label-order locality, in-cell evaporation and the gas↔gas vapor exchange.
Engine commit on ORGE-ENGINE `issue/41` (merged into its `rebuild`), gitlink bumped and `liborge.so` rebuilt
in the ORGE PR that closes #41. `DESIGN-LAW.md` is not edited: everything here is inside law v5.

### 20.1 What landed

| Item | Where | Notes |
|---|---|---|
| 1. §4.6 slot routing of the bulk flux (term (1)) between different species sets | `engine_b.hpp` `resolve_world` (grant pre-pass, `routed`, R1 / R1.5 / R2) | A face between two occupied cells of different labels, at least one a composition, carries the bulk flux when the two species sets share a species and every mobile species the donor brings that the receiver lacks (its *novelty*) has a slot there. Each receiver grants ONE novelty per tick — the largest Σ inbound intent `ρ_d·max(0,−W)·A·dt`, tie → the lexicographically lower set — when its free slots hold it, never its last free slot while a resident component's phase partner is neither resident nor in the novelty (§4.6 reservation). A donor whose novelty is not the grant is clamped to 0 on that face, never dropped. Two one-material cells of different species share none: their face stays no-flux, exactly as before. |
| 1. per-component yield gate ψ_k on the bulk share | R2 commit | `g_k = mobile_k·max(0, F − τ_y,k)/F`, `F = |ṁ|·|W|/dt`; only the mobile content moves (`ṁ ← ṁ·Σy g/Σy`), so a skeleton (stairs, rails, grass, soil) is never carried while its pore fluid moves. An all-fluid donor has `g ≡ 1` exactly: bit-identical to B1. A partly immobile donor carries its mobile components' enthalpy (blended by the immobile share). |
| 1. per-species deltas keyed by species | `CellAccum::dmIx/dmk/nk`, `acc_add_dmk`, `acc_species_dm`, `mix_end` | A routed face lands species the receiver did not hold. `mix_end` composes a composition cell from its pre-step slots + the per-species deltas, and a one-material cell that received foreign species from DECODE's committed mass less those arrivals + each arrival. Seepage and the vapor exchange read the per-species deltas and count the arrivals' slots. |
| 1. §6.3 displacement of a species set | displacement pre-pass | The displaced incumbent may be a composition (humid air): it is evicted whole with every component into the cell above, which may be vacuum, the same set, or a set that shares a species and grants the rest a slot (the eviction takes the cell's grant when it has none). Material reads use the cell's own mixture; an immovable composition (μ = +INF) is never displaced. The liquid quantum stays one-species. |
| 1. the switch | `Globals::g_vap` | Default `G_VAP_PENMAN` (was 0). |
| 2. locality (law #4) | R0 swap selection | The greedy swap selection sorted candidates by priority alone with an unstable `std::sort`, so tied candidates were ordered by the whole candidate list: a pair appearing in a far chunk changed which swap fired in a region (`engine_b_mmc_state_test` (B) with vapor on: first difference at tick 5, air cells x=7 z=6 y=24..27). The order is now total (priority, then the pair's world position). The issue's first reading — the composition-label numbering — was ruled out: offsetting every label by one row changes nothing. |
| 3. P2 in-cell sub-saturation evaporation (proposal §4.7 P2) | `mix_end` after P1 | Law v5 #9 path 3 (an in-cell phase transfer, the identity on E) in DECODE (law #4): `Δm = θ_ph·min(m_c, m_sat − m_h, χ_pair·g_vap·A·w_liq·w_gas·max(0, p_sat(T) − p_v)·dt)`, `θ_ph = 1 − e^{−dt/τ_ph}`, `τ_ph = 1 s` (§12 Q6), with the vapor exchange's interface weights. Floored to the slots' mass lattice and ε_mass. |
| 3. gas↔gas vapor exchange (§4.5 term (3) between two gases) | `vap_pieces` (third piece), `vap_world` marking | `F = χ_pair·g_vap·A·dt·w_gas,d·w_gas,r·(p_v,d − p_v,r)`, carrying `h_vapor(T_donor)`; faces are visited only where the piece can reach ε_mass, so a uniformly humid atmosphere at rest costs nothing. |

### 20.2 Choices made here (documented, not law changes)

- **Routing rule = shared species + granted novelty.** The proposal lets any species flux into another cell's
  open volume; this slice opens only faces whose sets share a species, so water and air (or water and humid
  air) keep exchanging by the swap and the §6.3 displacement as before. Opening those faces is a much larger
  behavioral change (every water surface would aerate) and is not what #41 asked for.
- **ψ_k's face force** is the face's momentum flux `|ṁ|·|W|/dt`; the receiver room stays the cell-level
  `max − m` of B1 (no per-component room on the bulk term).
- **Rounding.** Like B1's same-label bulk flux, a routed face writes `ṁ·y_k` into f32 slots without the mass
  lattice: per-species books hold to f32 storage rounding (≤ 1e-6 of the total), not to the bit.
- **Item 4 (one-material boiling-point elevation, §12 Q3)** is out of Phase 3 scope (owner decision on #41):
  recorded, not built.
- **Dry-subject tests run at `g_vap = 0`.** Scenes whose subject is a dry atmosphere or a one-material body at
  rest set the knob explicitly, as #41 anticipated: `engine_b_stage1_rest_test`, `engine_b_a9_probe_test`,
  `engine_b_gas_test` INV-AL, `engine_b_inv_al_smoke_test` rest-cold (plus a new humid twin that runs at the
  Penman value and rests), `engine_b_way2_rest_balance_test` REST and ANTISYM gas|liquid,
  `engine_b_pressure_relax_test` INV-P1 (a humid top cell sits its vapor partial pressure higher, so the
  dry closed form ρg(k−½)dx shifts by p_v), `engine_b_advect_enthalpy_test` (2), `engine_b_bug3_accept_test`
  (1). `engine_b_stage2_leveling_test` FLAT keeps the exchange on and counts H₂O (water + steam + ice + ε
  ledger) instead of liquid water.
- **Phase chain in the species delta.** `apply_species_delta` (seepage / vapor exchange, DECODE side) finds a
  debited species along its phase chain two hops each way: a falling vapor wisp can condense and freeze in one
  tick, and the vapor exchange's debit then lands on the ice (`engine_b_stage2_lava_sink_test` aborted on the
  budget assertion once the exchange ran by default).
  `engine_b_horizontal_flow_test` LEVEL2-AIR and `engine_b_gas_test` INV-GAS run with the exchange on and pass.
- **Open-top atmosphere over a pool (A-9 with vapor on)** still grows one ~0.2 kg/cell layer into the open
  top as the evaporating pool raises its Dalton pressure; routing does not change that. Filed as #72.

### 20.3 Evidence

`ORGE-ENGINE/tests/engine_b_mmc_route_test.cpp` (cheap tier), real LUT, books every tick: (A) a compressed
humid cell vents vapor and air into a dry row (4.4e-3 kg of 0.012 kg of steam in 30 ticks, reaching the
second dry cell); (B) a routed face carries the donor's composition (receiver steam:air 0.00999999 vs 0.01);
(C) a one-high water body displaces a humid cell and its vapor is evicted upward with it; (D) a receiver
between two novelties admits exactly one on the first routed tick; (E) fog in dry air re-evaporates in place,
water lost == steam gained every tick, the cell cools against its g_vap = 0 twin; (F) humid and dry air at
the same Dalton pressure exchange 6.0e-4 kg in 20 ticks (Penman order 6.2e-4; the bulk flux alone 1.4e-6).
RED on the base engine: (A)–(F) all fail (10 checks). `engine_b_mmc_state_test` (B2): with the vapor exchange
on, a far species set leaves a region bit-identical — RED on the base engine (first difference tick 5).

## 21. Open-top humid atmosphere (issue #72)

The A-9 scene with the vapor exchange on (a water pool, 6 air layers, the top left as vacuum) grew a
~0.2 kg/cell air layer into the open top. Engine commits on ORGE-ENGINE `issue/72` (merged into its `rebuild`),
gitlink bumped and `liborge.so` rebuilt in the ORGE PR that closes #72. `DESIGN-LAW.md` is not edited: the
fix applies law #9 as written.

### 21.1 Cause

Not the mixture EOS. A humid cell's Dalton gauge is right: 0.6 g of steam over 1.2 kg of air reads its vapor
partial pressure (tens of Pa) above the dry ladder, and the `mix_material` rest anchor P₀,cell gives that
value. The trouble is what the rise does at the open top:

1. The bottom humid layer sits ~15 Pa above the dry ladder. The relaxed column above it is pushed up by
   ~1e-5 m/s, so the top row's drive points into the vacuum above it.
2. A gas could seed vacuum with any drip ≥ ε_mass. The R2 empty-refill gate's floor was the
   cohesion-weighted `(1−χ)·minMass` (≈ 2e-4 kg for air), and its test `|ṁ| < floor − 1e-3` never fires at
   that floor. At tick 14, 30 cells above the column took ~1e-6 kg of air each.
3. A sub-min gas cell's anchor sits at the 1%-of-rest floor (`gas_anchor_v42`, ≈ −991 Pa for air), about 80
   times the 12 Pa per-cell ladder. Each seed drew the cells below it to their donor floor (1.2 → 1.0 kg).
   That gave 51.09 kg over 256 cells by tick 17, which then held.

The dry A-9 run passes only because its rest is exactly balanced: the top row's drive is never positive.

### 21.2 What changed

| Item | Where | Notes |
|---|---|---|
| Empty-refill floor = the full `minMass` for every species | `engine_b.hpp` `resolve_world` R2 (the §6.4 / A-10 gate) | Law #9 (INV-SOFTWIN): flow never pushes a component out of its window, and `min ≤ m ≤ max ∨ m = 0` holds per component, a gas included. A refill below min is gated, or rounded up to one min chunk by the §6.4 min-quantum refill when the donor can spare it. `(1−χ)` stays where the law puts it: the cohesive pull of the soft window. |
| The full-drain split | R1 (`minTgtI`) | The face shares a full drain may split into empty cells use the same floor, so R1's drain decision and R2's gate agree. |
| The wall's empty-neighbour room | `wall_pressure` (`quantum`) | The same refill chunk, so the soft wall pushes into vacuum only when the donor can seed it. For χ = 0 rows this is bit-identical (it was already the full min). |
| The §6.4 round-up needs a donor DECODE moves | R2 refill gate | DECODE holds a cell at the exact μ→∞ mobility limit (`mobility_frac(μ_eff, defaultMass/V) ≤ 0`) at its snapshot mass, so a round-up from that cell would credit the receiver a chunk the donor never pays. On the real LUT every fluid has finite μ, and terrain never reaches the gate (its drive is 0), so nothing changes there. A LUT row with rest density 0 does reach it: DECODE reads mobility 0 for the row while its face to vacuum is open by ~5e-8. Found by `ResidentLutParityIT` (the JNI determinism golden), whose synthetic LUT leaves `defaultMass` at 0. With the first fix alone, its 30 kg air cell's 5.6e-7 kg drip was rounded up to 1 kg, and the first step made 1 kg of air (3030 → 3031 kg). |

A gas still expands into vacuum: by a donor that can spare one min chunk (compressed air ≥ 2 kg) or by a full
drain. A rest-density atmosphere (1.2 kg, 0.2 kg of slack) cannot seed its open top, so it takes up the
Dalton rise as pressure. This is the A-9 claim, "the universal min-floor bounds the expansion". The §6.3
eviction into vacuum already required a quantum of at least min (`evict_surplus`).

### 21.3 Evidence

`ORGE-ENGINE/tests/engine_b_a9_probe_test.cpp` `test_open_top_humid` (cheap tier), real LUT, g_vap at its
default: the pool evaporates (0.86 kg of steam in 120 ticks), nothing ever lands above the column (0 kg), no
air component goes below minMass (lowest 1.198 kg), the top row rests (‖u‖ 6e-5 m/s). RED on the base engine:
51.09 kg above the column, lowest air component 1e-6 kg (2 checks). The dry A-9 run keeps g_vap = 0 (its
subject is a dry atmosphere).

`ORGE-ENGINE/tests/engine_b_refill_mobile_donor_test.cpp` (cheap tier) runs the `ResidentLutParityIT` scene
through the JNI body (`orge_step_world`, 8 calls). Grand and air mass hold exactly. RED with the first fix alone:
1 kg of air created.

`engine_b_accept_test` TEST3 (the inverted air/water/lava stack) now stands between two stone side walls. Its
open-floor "settles quietly" check had passed only because two sub-min air cells (0.202 kg) formed beside the
stack and walled the lava in. Without them, the lava (τ_y = 0) spreads along the open row, and the slow
lava|water two-layer leveling it starts still moves 2–9 kg per 10 ticks after tick 200. That leveling is not
the test's subject. Within the walls the stack ends lava < water < air, every cell in its window, with mass
moving 1e-3 kg per 10 ticks. Assertions and thresholds are unchanged.

## 22. Drift test (d) on the rest freeze and the refill guard (issue #79)

### 22.1 What changed

The rest freeze (#73) and the §6.4 refill guard (#72) each forked on a computed classification. Both now use
continuous weights. Thresholds remain only on a cell's state or on a flux bound.

| Was | Now |
|---|---|
| `movOf[k] = mobility_frac(…) > 0 ? 1 : 0`, then `if (!(a.mov & b.mov)) return true` | each cell carries its bulk mobility `mob = Π_k mob_k` (`mobility_frac`, exactly 0 at μ → ∞; a rigid component holds the whole cell, as +INF dominates the mixture μ). Every mechanical residual of a face is multiplied by `w = mob_a·mob_b`, the bound on what the bulk flux can carry, so a rigid side contributes nothing and the face is judged on its heat (`|ΔT|`) alone. |
| `if (a.sp != b.sp) return false` | `w · (1 − Σ_species min(y_a, y_b)) ≤ rest_dy`. The composition distance is 0 for one composition and 1 for disjoint species sets, so an interface between two mobile species sets stays awake. |
| `rest_cells` skipped `cell_mixed(C, i)` | every component must sit inside its own mass window and phase band, and the in-cell exchange weight `(1 − Σ y_k²) · max_k mob_k` must be `≤ rest_dy`. That weight is the impurity carried by the most mobile component, the drive of P2, the phase partition and pore slip. It is exactly 0 for one species and for an all-rigid composition. A frozen composition loses its per-cell mixture in the snapshot, so every pass reads the rest row; `mix_end`, seepage and the vapor exchange skip it. |
| R2: `mobDG = mobility_frac(…)`, then `mobDG > 0.0f && …` | R1 weights the donor's §6.4 slack by its mobility: `mobility_frac · max(0, m − min − Σ f)`. A donor DECODE holds spares exactly 0, so the top-up clamps against its spendable slack. A fluid spares all but ~5e-7 of it. |

`Globals::rest_dy = 1e-6` (a mass fraction) is the one new knob. With pure species, the freeze does what it
did before: the idle range-1 scene still freezes every cell. The CARRY (192349..196320 cells/step) and WAKE
(98272) frozen counts are identical to the base engine. Humid air still stays awake, because its vapor component sits below its condensation threshold,
outside its phase band. The idle step costs ~57 ms against ~45 ms on the base engine (5 columns, loaded host,
`engine_b_perf_bench 5 4 1 0`), which is within #73's 2× bound.

### 22.2 Evidence

`ORGE-ENGINE/tests/engine_b_rest_freeze_mmc_test.cpp` (cheap tier):
- A static scan of `engine_b.hpp`, with comments stripped, finds no `movOf`, `.mov`, `cell_mixed(` or `.sp` in
  the rest-freeze block, and no `mobility_frac` / `mu_eff` in the §6.4 refill block.
- A resting rigid composition (rail + grass) set into a superflat floor freezes and is carried bit-exact
  through the snapshot / `mix_end` path, with grand and per-species mass exact.
- Against the base engine it fails 6 checks: the 5 static ones, plus "0 of 128 composite cells frozen".
- With the `mix_end` frozen-cell skip removed, it fails 3 checks: the grass component is destroyed.

`engine_b_rest_freeze_test` and `engine_b_refill_mobile_donor_test` pass unchanged.

## 23. Steam under sunk lava (issue #90)

### 23.1 Cause

On the #88 scene (1473 K lava sinking through a 7×7×3 water pool, real LUT), contact boiling (#87) puts steam
into the free volume of the lava film's cell. That steam had no exit, and the cell that held it was then moved
whole:

- **No exit.** The §4.6 route opens only between species sets that share a species. `{lava, steam}` and
  `{water}` share none. The gas slip of §4.5 term (2) was wired only through a porous skeleton (`s > 0`).
  Single cells reached 5 kg of steam by 10 s (gauge P ≈ 1.4 MPa, which kept the whole pool sloshing at
  ~3 m/s) and 18–260 kg later.
- **The lift was the §6.3 displacement, not the swap.** The composite has a mixture χ > 0, and its bulk density
  (663 kg/m³) is below water's. So a lateral water neighbour "displaced" it as if it were a gas pocket, which
  evicted the whole incumbent, lava included, up into the water cell above. The water cell had been granted the
  slot because it held a trace of steam. The water left behind in the receiver flashed to steam (102–290 kg in
  one tick, and once T went negative). This is how the rock layer moved up to y = FY+2.
- **Entrainment rode the §4.6 route.** Once a water cell held steam, `{lava, steam} | {water, steam}` shared
  steam. The bulk flux (`f_k = y_k·ṁ`) then carried lava up into the water, and carried water down into the
  lava's free volume, where it flashed.

### 23.2 What changed (one universal mechanic each; no species or state branch)

| Path | Change |
|---|---|
| §6.3 displacement candidacy | A cell whose parcel outweighs its bulk mass (`parcel_mass` > M: a condensed body with free volume, #88) is not a gas pocket and is never evicted whole. A gas cell's parcel is its mass, so gas cells are unchanged. |
| §4.6 novelty | A mobile condensed novelty is never routed into a cell holding a mobile condensed component of another species: weight `(1−χ_k)(1−χ_j) ≥ ½`. Two immiscible bodies exchange by the whole-cell swap, as two one-material cells do. A gas novelty or a gas resident weighs ≈ 0, so humid air is unchanged. |
| Seepage: gas slip out of a body | Gas-slip weight `g = (1 − M/parcel)·clamp(Σ χ_k n_k / n_film, 0, 1)`. A gas (χ-weighted) slips through a face where `max(g_d, g_r) > 0` **into its own liquid**: the receiver holds k's cold phase partner (`minTarget`), not k (`seep_land`). It lands there as that liquid (condenses) and carries `h_k(T_d)`, so the latent heat goes into the receiver. Its drive is its own partial pressure `n_k·R·T/V_free − P_atm` against the liquid's pressure, so a trace of vapor in humid air never enters a liquid. The cap is half of the donor-side equalizing mass (the vapor raises no pressure in the receiver). It never opens a slot. Gas↔gas movement stays with the bulk flux and the vapor exchange. A World with a composition but no skeleton row now runs the seepage pass. |

A non-condensable gas held by a submerged body (air under lava under water) still has no exit. That needs the
counter-current, volume-zero-sum liquid half of term (2), which is not part of this change.

### 23.3 Evidence

`ORGE-ENGINE/tests/engine_b_steam_lift_test.cpp` (cheap tier, real LUT):
- **(A) the #88 hot pool, 60 s.** The lava sinks into the two lowest rows by step 5 and stays there: 2650 kg on
  the floor row at 60 s, 0 kg above the two lowest rows. After 10 s no rock-bearing cell holds more than 1.6 kg
  of gas, or more than 4.4× its gas share at the local pressure ((P_atm + P_neighbour)·V_free·M/(R·T)); the
  ratio is ~3.8× at 60 s. Per-family mass is exact and grand E closes.
- **(B) a 600 K phase-inert lava body holding 5 kg of steam, contact boiling off.** The steam condenses into
  the part-filled water above it. Within 4 s the body holds no more than its gas share (0.282 kg). The lava stays
  in place, H2O family mass is exact, and the books close.
- On the base engine, case A fails 4 checks: rock lifted (91 kg above the lowest rows, 133 kg left on the floor
  row), 298 kg of gas, 794× the share. Case B fails 2 checks.

The steady state is not 1× the share. The steam leaves by condensing into the water around the body. A near-full
pool opens room (max − m) for it only as fast as the water flows. The quenched rock is closed to the bulk flux, so
its gas cannot push the water away.
