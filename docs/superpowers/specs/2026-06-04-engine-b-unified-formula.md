# Engine B — Unified Energy-Vector Engine — explicit formulas (companion to the design spec)

**Date:** 2026-06-04
**Status:** **DRAFT for user review.** Companion to `2026-06-04-engine-b-velocity-field-design.md` (RATIFIED).
That spec is the *model*; this document is the *math* — it upgrades §2/§4 from design-level to explicit
formulas and closes the nine open formula questions from the handoff.
**Track:** parent `rebuild` ↔ engine `rebuild` (worktree `/home/claude/ORGE-B`).

> **The pipeline, in the user's three words:** **Encrypt** (per cell, own state only) → one energy-flux
> vector `E = (Ex,Ey,Ez)` written to the grid. **Resolve** (the only cross-cell step) → nets the vector
> map over `dt` into per-cell energy/mass/heat exchange. **Decrypt** (per cell, own state only) →
> re-partitions the result back into mass / temperature / motion. One unified rule, every cell, no
> species/phase/state branch.

---

## §A — State: the energy partition

Everything a cell *is* is energy in three forms. We store the partition; the vector is derived each tick
and never stored.

**Stored per cell (the partition):**

| symbol | meaning | energy form |
|---|---|---|
| `m`  | mass (kg)            | rest store, `E_mass` (carrier of all advective energy) |
| `T`  | temperature (K)      | thermal `E_th = m·c·T` |
| `u = (ux,uy,uz)` | bulk velocity (m/s) | kinetic `E_kin = ½m‖u‖²` |
| `s`  | species id           | label, advected cargo |

`u` is the only **new** persisted channel vs Engine A (`+3×float16 = 6 bytes/cell`; defaults 0 on
old-region load — a resting, conservation-neutral start). `m`, `T`, `s` already persist.

**Material (unchanged schema):** `c` = heat_capacity, `k` = thermal_conductivity, `M` = molar_mass,
`μ` = viscosity (drag), EOS triple `m_min, m_0 (=default), m_max`. Cell volume `V`, gravity `g` (down = −ŷ).

Derived each tick: `ρ = m/V`.

---

## §B — ENCRYPT  (Pass 1: parallel, own state only → `E = (Ex,Ey,Ez)`)

One function, zero neighbor reads, pure map (GPU-ideal). **Gravity aims** the vector; thermal sets its
**amplitude**; **pressure** is isotropic from own state so it aims in Resolve, not here (§B.3) — a faithful
reading of the user's rule once "direction needs a neighbor" is taken seriously.

### B.1 Equation of state (own `m`,`T` → scalar pressure `p`)
```
χ      = (m_max − m_0) / (m_max − m_min)                 ∈ [0,1]    # 0 = liquid … 1 = gas (continuous)
m_rest = clamp( m_0·(1 − α·χ·(T − T_ref)/T_ref),  m_min, m_max )    # heat lowers rest density
p      =  m ≥ m_rest ?  K·((m − m_rest)/(m_max − m_rest))^γ          # compression → stiff wall at m_max
                     : −K·χ·((m_rest − m)/(m_rest − m_min))          # expansion (gas) → toward m_min
```
`p = 0` exactly at `m = m_rest` ⇒ a cell at rest density exerts no gauge push (free surface for liquids).

### B.2 Amplitude `h` (scalar — thermal + pressure-work + kinetic, per kg)
```
h = c·T  +  p/ρ  +  ½‖u‖²            # stagnation specific energy. Thermal is pure amplitude (no direction).
```

### B.3 Direction `w` (m/s — aimed by motion + gravity; pressure aims in Resolve)
Only **gravity** has an intrinsic direction from own state; **pressure and heat are isotropic** (no axis
until a neighbor exists), so they aim in Resolve where the gradient lives — not here. Gravity is applied as
a body impulse **once per tick, here** (it then both aims the vector and seeds Decrypt's momentum):
```
u_g = u + dt·g·ĝ_down                 # gravity body impulse (uniform accel a=g, mass-independent)
w   = u_g                             # the vector's direction = current motion + gravity
```
Pressure does **not** aim `w` (an own-pressure buoyancy proxy was tried and *fails hydrostatic balance for
interior cells* — see §J.5). Pressure instead drives momentum via the exact high→low flux in Resolve
(§C.2); that changes `u`, which aims the vector next tick. So pressure *does* have direction — realised
where a gradient can actually be measured. `u_g` is the gravity-updated velocity Decrypt (§D.2) starts from.

### B.4 The vector + viscous drag
```
E = ρ · h · w / (1 + dt·λ)           # λ = μ/(ρ·Δx²)  → dt·λ dimensionless (units pinned, §G.3)
```
- **Frozen material** (`μ → ∞` or absent): `λ → ∞` ⇒ the advective vector `E → 0`. The cell still
  participates in conduction (§C.3, which does not use `E`). This is design-law **L2** with no branch.
- A **light** cell (small `ρ`) emits a **weak** `E`; it cannot overpower a heavy cell's strong `E` in
  Resolve. *This is the structural fix for the air-pushes-water bug — by construction, not by a density gate.*

Also exposed to Resolve (read from the stored partition, not re-derived): `ρ`, `p`, `T`, `u_g`, `h`, `s`.

---

## §C — RESOLVE  (Pass 2: the only cross-cell step — net the vector map over `dt`)

For each of the 6 faces of cell `i` with neighbor `j` (area `A`, unit normal `n̂` pointing `i→j`,
`Δx` spacing). Every channel is **antisymmetric** (what crosses `i→j` is the negative of `j→i`) from a
single pre-step snapshot ⇒ conservation by construction (design-law **L7**).

### C.1 Face drive velocity (scalar, signed along `n̂`)
```
W = ½ (w_i + w_j)·n̂                   # face-normal drive speed = averaged drive of the two cells
donor = (W > 0) ? i : j               # upwind
```
`w` is recovered from the written energy vector by `w = E·(1+dt·λ)/(ρ·h)` (or read directly); the energy
vector `E` stays the primary object on the grid, `w` is its normalized drive. Netting opposing faces is the
discrete divergence of the energy field "by `dt`".

### C.2 Advective channel — moves mass + momentum + advected heat (antisymmetric)
```
ṁ      = ρ_donor · W · A · dt                      # MASS flux  — exact: out of donor = into receiver
p⃗_adv  = ṁ · u_g,donor                             # MOMENTUM carried by that mass (gravity-updated u_g)
E_adv  = ṁ · h_donor                               # ENERGY carried by that mass (thermal+kin+pwork)
species: the donor's `s` rides with ṁ (commit in §D.5)
```
Plus the **pressure-gradient kick** in conservative **flux form** ("pressure has direction" = high→low):
```
p_face  = ½ (p_i + p_j)                            # face pressure (free-slip wall ⇒ p_face = own p)
Δp⃗_pres(i) += − p_face · A · dt · n̂_out            # i loses momentum across each outward face
                                                   # Σ over the shared pair = 0 (conserved); = −∮p dA = −∇p·V
```

### C.3 Diffusive channel — conduction, mass-free (antisymmetric; the unified `q`, L8)
Thermal is amplitude; Resolve turns the amplitude **difference** into a direction. The driving potential is
**temperature** `T` (the intensive amplitude of thermal energy — two equal-`T` cells of different mass do
**not** conduct):
```
q = k_face · (T_i − T_j) · (A/Δx) · dt             # exact Fourier; k_face = harmonic mean(k_i,k_j)
```
Works from rest (`u=0, W=0`) — pure Fourier conduction, no mass motion. The energy `q` heats the receiver
via its own `m·c` (§D.3). This is the entire replacement for `orge_kernel.hpp`; **no separate conduction pass**.

### C.4 Per-cell accumulation (the "new vector map result")
Summing the six faces gives cell `i` its resolved exchange:
```
Δm_i = Σ_faces (±ṁ)                              # net mass            (signed: + into i)
Δp⃗_i = Σ_faces (±p⃗_adv − p_face·A·dt·n̂_out)      # advective momentum + pressure flux (−∇p·V); gravity in u_g
ΔE_i = Σ_faces (±E_adv ± q)                       # net energy (advective + diffusive)
```

### C.5 Absorb vs reflect (incompressibility, emergent — §9 handoff Q4)
No branch. An incompressible receiver (own `χ≈0`) climbs the stiff `^γ` ramp the instant `m` exceeds
`m_rest`, so its `p` spikes; next tick `p_j ≫ p_i` and `p⃗_pres` **reverses** — the donor's push is
returned and redirected to whatever open face has the lowest `p`. A compressible receiver (`χ≈1`) barely
raises `p`, so the flux **passes through** (it compresses). Reflection redirects momentum, never creates it.

**Capacity backstop:** clamp `ṁ` so a receiver does not exceed `m_max` within the tick; the `^γ` wall makes
this rare, and transient overshoot relaxes next tick via `p`.

**Free-slip walls (§9.D):** terrain faces pass `ṁ = q = 0` and no tangential drag.

---

## §D — DECRYPT  (Pass 3: parallel, own state only → new `m, u, T`)

Each cell unpacks its resolved `(Δm_i, Δp⃗_i, ΔE_i)` into its own partition. Pure map, GPU-ideal.

### D.1 Mass and species
```
m' = m + Δm_i                                       # exact (Δm is antisymmetric sum)
s' : if net advective inflow is from a different species, see §D.3
```

### D.2 Momentum → velocity (inertia persists)
```
u' = (m·u_g + Δp⃗_i) / m'                            # start from gravity-updated u_g (gravity once); momentum-
                                                     # conserving; u' carries to next tick (sloshing/inertia)
```

### D.3 Energy → temperature (what's left after motion)
```
E_kin'   = ½ m'‖u'‖²
ΔE_kin   = E_kin' − ½ m‖u_g‖²                        # KE change from advection/pressure (gravity baseline u_g)
ΔE_th    = ΔE_i − ΔE_kin                             # the remainder is heat: advected internal + conduction
                                                     #   + KE lost in momentum mixing (viscous dissipation)
T'       = T + ΔE_th / (m'·c)                        # new temperature — closes TOTAL-energy conservation
```
Total energy (internal + kinetic) is conserved because every joule in `ΔE_i` lands in either `E_kin'` or
`ΔE_th`; KE lost when two streams merge becomes heat (physical viscous dissipation), never vanishes.

### D.4 Vacuum & velocity guards (near-empty cells — red-team #3)
Mass and momentum leave a donor at the **same** velocity, so an emptying cell keeps a finite `u` (=`u_g`),
never a spike — from advection. The pathological case is a **pressure kick into a near-zero-mass cell**
(`Δp⃗/m' → ∞`). Two cheap guards close it:
```
m_floor = max(ε, min_mass)                          # ε ≈ 1e-6 kg
if m' < m_floor:  u' = 0 ;  cell is VOID (p from EOS ≈ min, no momentum)   # kills the m→0 blow-up
u' = u' · min(1, (Δx/dt)/‖u'‖)                       # CFL speed cap: never move >1 cell/tick
```
The clamp/void rarely fire; the tiny momentum they discard is picked up by the reconciler (L7).

### D.5 Species commit under L6 sharp (handoff Q6)
One species per cell. If a cell's mass goes to ~0 and refills from a different-species inflow, it
**relabels** to the donor species (full-cell swap is the `Δm` total change). If same species, **merge**
(mass adds, `T'`/`u'` are the mass-weighted blends above — already conservative). Species rides purely as
advected cargo on `ṁ`; Resolve needs only the donor's `s`, never a comparison. Numerical label smear at a
sharp interface is deferred to a later anti-diffusion sharpening step (spec §11), not modeled here.

---

## §E — Conservation (proofs)

- **Mass:** `ṁ_ij = ρ_donor·W·A·dt` and `ṁ_ji = −ṁ_ij` (same `W`, opposite `n̂`, same donor) ⇒
  `Σ_cells Δm = 0` to FP. **Exact.**
- **Momentum:** `p⃗_adv` and `p⃗_pres` are antisymmetric per face; gravity adds a uniform body impulse
  `m·g·dt`. Reflection (C.5) only redirects. Conserved up to the free-slip boundary.
- **Energy:** `E_adv` and `q` antisymmetric per face ⇒ `Σ_cells ΔE = 0` to FP. Energy cannot be created ⇒
  no blow-ups (the §5 stability argument).
- **The reconciler** (Java, L7) is now a *backstop only* — it should find ~0 residual; if not, that's a bug.

---

## §F — Mapping to the handoff's nine open questions

1. **Pass-1 `J_E` construction** → §B. Gravity = impulse into `w` (no global-z term; only `Δ` matters);
   pressure & thermal are isotropic from own state ⇒ their direction is set in Resolve (pressure flux /
   conduction), not in `w`; thermal magnitude rides in amplitude `h`. ✔
2. **Pass-2 antisymmetric face flux** → §C.1–C.3, proven antisymmetric in §E. ✔
3. **The energy decomposition (biggest)** → §C.2 splits the face transfer into advective (`ṁ·h`) vs
   diffusive (`q`); §D splits advective into `Δm` / `Δp⃗→Δu` / `ΔE_th→ΔT`. Conserves mass **and** energy. ✔
4. **Absorb vs reflect** → §C.5, emergent from the EOS, no branch. ✔
5. **Advective mass amount + hard wall** → §C.2 (`ṁ`), §C.5 capacity clamp + `^γ` wall. ✔
6. **Species commit under L6** → §D.5: cargo on `ṁ`, relabel/merge, no comparison. ✔
7. **Conduction `q` with no temperature sharing** → §C.3: amplitude-difference Fourier, mass-free,
   from rest. ✔ (Resolve reads the *amplitude*, exactly as "thermal has amplitude, not direction" demands.)
8. **Tunables + calibration** → §G. ✔
9. **Velocity persistence & units** → §A (persist `u`), §B.4 drag denominator, §G.3 dimensionless `dt·λ`. ✔

---

## §G — Tunables & calibration

### G.1 Globals (not material fields)
| sym | role | initial | calibrate against |
|---|---|---|---|
| `K`   | EOS stiffness | tune so a 1-cell water column's `p` balances `ρgΔy` at `m≈m_0` | hydrostatic rest (test 1) |
| `γ`   | wall sharpness | `≥ 2` (start 3) | incompressible pinning (test 4), overshoot |
| `α`   | thermal-expansion gain | `≈ 1` | gas thinning on heat / convection (test 9) |
| `T_ref` | EOS reference temp | 288 K | so `m_rest ≈ m_0` at room temp |
| `k`-scale | conduction rate | match old kernel's relax rate | Fourier (test 10) |
| `λ`-scale | viscous drag | from material `μ` | sloshing decay (test 7) |

### G.1b Acoustic CFL — the hard ceiling on `K` (red-team #1)
`K`/`γ` are bounded not only by hydrostatics but by the **artificial sound speed**
`c_s = √(∂p/∂ρ)`. At the stiffest expected compression this must stay sub-CFL:
```
c_s · dt_max / Δx ≤ 1     ⇒  with Δx=1 m, dt_max=0.5 s:  c_s ≤ 2 m/s
```
This is the **pseudo-compressibility tradeoff**: the fluid is deliberately *soft* (artificial `c_s`, not
water's real 1500 m/s) so the explicit step is unconditionally stable and no shockwave can outpace `dt`. The
capacity clamp (§C.5) is the hard backstop against gross overfill; a genuine over-squeeze relaxes over many
ticks (§6 gradual-equalisation tradeoff), it does not detonate. A stiffer/faster wall would need sub-cycling
or an implicit pressure solve — **banked**, not in v1.

### G.2 Calibration method
Bisection per global against its single acceptance test, holding others fixed, in the order
`K → γ → α → k-scale → λ-scale` (each is dominant in its own test), then a joint soak (test 11).

### G.3 Units (pin `dt·λ` dimensionless)
`μ` carried as a kinematic drag with units `[1/s]` after `λ = μ/(ρ·Δx²)` (cell `Δx=1 m` ⇒ `λ = μ/ρ`).
Then `dt·λ` is dimensionless and the implicit denominator `1/(1+dt·λ)` is unconditionally stable.
`+∞`/absent `μ` ⇒ `λ=∞` ⇒ advective `E=0` (frozen). Calibrate the `μ→λ` scale so water sloshes a few
cycles and lava barely moves.

---

## §H — Decisions log (calls made this session, user-delegated)

- **D1 — mass is exactly conserved**, not reconciler-patched: upwind antisymmetric face flux (§C.2). The
  energy vector still drives direction+amount; the stored partition tells Resolve the mass it carries.
- **D2 — only gravity aims the vector in Encrypt; pressure aims in Resolve.** Pressure and heat are
  isotropic from own state, so they get their direction from the high→low / hot→cold flux in Resolve, which
  is exact (§J.5 proof). An own-pressure buoyancy predictor in `w` was tried and *removed* — it breaks
  hydrostatic balance for interior cells. Pressure still "has direction" — realised where a gradient exists.
- **D3 — conduction is the amplitude-difference channel** in Resolve (§C.3): "thermal has amplitude, not
  direction" → Resolve supplies the direction. One Resolve step, no separate conduction pass (L8).
- **D4 — Resolve may read the stored partition** (`ρ,p,Φ,u,s`) of both cells; only Encrypt and Decrypt are
  strictly own-state. This is what makes mass/momentum exact while keeping `E=(Ex,Ey,Ez)` the written
  inter-cell object. (Loosens the older spec's strict-`J_E`-only L9 to "Encrypt/Decrypt are cross-free.")

---

## §I — Acceptance tests these formulas must pass (from spec §10)

1 flat-rest, 2 vessels, 3 buoyancy order, 4 tube pinning, 5 bubble/plume, 6 incompressible displacement,
7 sloshing/inertia, 8 gas-fill vs liquid-ceiling, 9 thermal convection, 10 pure conduction (Fourier),
11 conservation soak (energy + per-species mass invariant), 12 perf vs Engine A.

Each maps to a stage (spec §11): core mechanical (1,3,4,6,8) → inertia+reflection (2,5,7) → thermal
unification (9,10,11) → tune+sharpen+perf (12).

---

## §J — Proof: Encrypt→Resolve→Decrypt ≡ the standard *separate* calculation

**Claim.** The unified pipeline is not a new physics — it is the standard separate finite-volume
conservation laws (continuity + momentum + internal-energy + Fourier), re-bookkept through one vector
object. Channel by channel, each Resolve term is **algebraically identical** to its separate-calc flux, so
the per-cell update is the same number. We show the identity, then three fully-worked examples.

### J.1 The "separate calculation" reference (textbook explicit FV, `Δx=1 m`, `V=1 m³`, `A=1 m²`)
```
(mass)      m_i⁺ = m_i − dt·Σ_f ρ_don (u·n̂) A
(momentum)  (mu)_i⁺ = (mu)_i − dt·Σ_f ρ_don u_don (u·n̂) A − dt·Σ_f p_face n̂_out A + m_i g dt
(internal)  (mcT)_i⁺ = (mcT)_i − dt·Σ_f ρ_don (cT)_don (u·n̂) A + dt·Σ_f k_face (T_j−T_i)(A/Δx)
```

### J.2 Term-by-term identity (with the §B–§D mappings, drag off for clarity: `λ=0`)
| separate-calc flux term | unified Resolve term | identical because |
|---|---|---|
| face velocity `(u·n̂)` | `W = ½(w_i+w_j)·n̂` with `w=u_g` | same central face velocity; donor = upwind sign of `W` |
| mass `ρ_don(u·n̂)A·dt` | `ṁ = ρ_don·W·A·dt` (§C.2) | term-for-term equal |
| momentum advect `ρ_don u_don(u·n̂)A·dt` | `p⃗_adv = ṁ·u_g,don` (§C.2) | `ṁ` already equals the mass flux |
| pressure `p_face n̂_out A·dt` | `−p_face·A·dt·n̂_out` (§C.2) | **identical flux form** (after Bug-2 fix) |
| internal advect `ρ_don(cT)_don(u·n̂)A·dt` | internal part of `E_adv=ṁ·h_don` | `ṁ·c·T_don`; `h`'s `p/ρ+½u²` parts are the flow-work+KE that §D.3 routes to `u'`/dissipation |
| Fourier `k_face(T_j−T_i)(A/Δx)dt` | `q = k_face(T_i−T_j)(A/Δx)dt` (§C.3) | **identical** (after Bug-1 fix), sign = direction of net flux |
| gravity `m_i g dt` | `u_g = u + g·dt` in §B.3, carried to §D.2 | applied exactly once |

Since every flux matches, the conservation laws (`Σ Δm = Σ Δp⃗ = Σ ΔE = 0`) hold identically, and the
**fixed points coincide** (hydrostatic `w=0`; thermal equilibrium `T_i=T_j ⇒ q=0`). With pressure handled
entirely in Resolve (§B.3 fix), the pipeline is **bit-for-bit textbook explicit FV** — no approximation,
no extra model choice. The unification is purely in the *bookkeeping* (one vector object, one rule), not in
the arithmetic.

### J.3 Worked example A — pure conduction (no motion)
Two immovable cells, `u=0`, `c=1000 J/(kg·K)`, `m=1000 kg`, `k_face=1 W/(m·K)`, `dt=1 s`, `T_i=400`, `T_j=300`.
```
SEPARATE:  Q = k(T_i−T_j)(A/Δx)dt = 1·100·1·1 = 100 J  (i→j)
           ΔT_i = −100/(1000·1000) = −1.0e-4 K ;  ΔT_j = +1.0e-4 K
UNIFIED:   w=0 ⇒ E=0 ⇒ ṁ=0, p⃗_adv=0, E_adv=0
           q = 1·(400−300)·1·1 = 100 J  (i→j)  [§C.3]
           ΔE_th,i=−100 ⇒ ΔT_i=−1.0e-4 ; ΔE_th,j=+100 ⇒ ΔT_j=+1.0e-4   [§D.3]
MATCH ✓   energy moved 100 J, mass unchanged, exactly Fourier.
```

### J.4 Worked example B — pure advection (mass moving, no gradient)
`ρ=1000` (V=1), same species & `T`, no gravity/pressure. `u_i=(+1,0,0)`, `u_j=0`, `A=1`, `dt=0.25 s`.
```
face velocity:  W = ½(1+0) = 0.5 m/s   donor = i
SEPARATE & UNIFIED identical:
  ṁ      = 1000·0.5·1·0.25 = 125 kg   (i→j)
  m_i⁺   = 1000−125 = 875     m_j⁺ = 1000+125 = 1125     (Σ = 2000 conserved ✓)
  p_adv  = 125·1 = 125 kg·m/s (i→j)
  u_i⁺   = (1000·1 − 125)/875  = 1.000 m/s   (donor keeps its speed)
  u_j⁺   = (0     + 125)/1125 = 0.111 m/s
MATCH ✓   standard upwind continuity + momentum advection, exactly.
```

### J.5 Worked example C — hydrostatic pressure (the conservation-critical one)
Interior water cell `M` with neighbor `A` above and `L` below, `ρ=1000`, `g=10`, `Δy=A=1`, `dt=0.25`,
`u=0`. Hydrostatic means `dp/dy=−ρg`, so pressure rises by `ρgΔy=10000 Pa` per cell downward:
`p_A=10000`, `p_M=20000`, `p_L=30000`.
```
SEPARATE momentum on M:
  pressure flux:  top face (with A,  n̂_out=+y, p_face=½(20000+10000)=15000)
                  bottom face (with L, n̂_out=−y, p_face=½(20000+30000)=25000)
  Δ(mu_y)_M,pres = −dt·[ 15000·(+1) + 25000·(−1) ]·A = −0.25·(15000−25000) = +2500
  gravity:        Δ(mu_y)_M,grav = m·g·(−1)·dt = 1000·10·(−1)·0.25 = −2500
  TOTAL Δ(mu_y)_M = +2500 − 2500 = 0          ⇒ no flow (hydrostatic) ✓

UNIFIED:  §C.4 uses the IDENTICAL pressure-flux form ⇒ Δp⃗_pres = +2500 ; gravity enters via u_g ⇒ −2500.
          Net momentum change 0 ⇒ u stays 0 ⇒ w=0 ⇒ E=0 ⇒ ṁ=0.   No flow.
MATCH ✓   bit-for-bit. Off-balance (say p_L too low), BOTH compute the same +Δ(mu_y) and M sinks; the
          unified pipeline carries no approximation here — it IS the separate pressure flux.
```
*(This is why the §B.3 own-pressure buoyancy predictor was removed: it would have given M a spurious
`w_y = dt(p_M/(ρΔy) − g) = 0.25(20−10) = +2.5 ≠ 0` — wrong. The Resolve flux is exact; the predictor was not.)*

### J.6 What the unification buys (and what it does not)
- **Does not** change the numbers vs separate calc — proven identical per channel.
- **Does** collapse four passes (advect / momentum / energy / conduction) into one snapshot+resolve, with a
  single per-cell vector as the only inter-cell object, no species/phase/state branch — the GPU + L1 win.
- The air-pushes-water fix is **emergent here too**: a light donor has small `ρ_don` ⇒ small `ṁ` and small
  `p⃗_adv`; example B with `ρ_i=1.2` (air) moving into `ρ_j=1000` (water) transfers `ṁ=1.2·0.5·0.25=0.15 kg`
  and momentum `0.15 kg·m/s` into 1000 kg — `u_j⁺ = 0.15/1000.15 ≈ 0.00015 m/s`. Physically negligible, by
  construction, with no density gate.

---

---

## §K — Numerical edge cases (red-team) & their guards

| # | Situation | Does it break? | Guard / answer | Where |
|---|---|---|---|---|
| 1 | **Over-compression** — cell squeezed hard | **No** — no real acoustics resolved | `c_s` capped sub-CFL (`K` bound) + capacity clamp on `ṁ`; over-squeeze relaxes over ticks, no shockwave | §G.1b, §C.5 |
| 2 | **Liquid–gas surface** — wave hits the interface | **No artificial bounce** | water `p≈0` above `m_rest` = true free surface; air is compressible ⇒ it *absorbs*, not reflects; χ-continuous EOS ⇒ waves transmit; artificial acoustic mode viscously damped | §B.1, §C.5 |
| 3 | **Near-vacuum** — `m → 0` holding momentum | **No infinity** | mass+momentum leave at same `u` ⇒ donor stays finite; void-floor `m'<ε ⇒ u'=0` + CFL clamp `‖u'‖≤Δx/dt` kill the pressure-kick-into-vacuum spike | §D.4 |
| 4 | **Moving sharp edge** (hot/cold, species) | **Blurs ~1 cell** with 1st-order upwind | accepted in v1; staged fix = slope-limited (MUSCL/minmod) flux for `u`/`T` + species-label sharpening (THINC/anti-diffusion) | §11 stage 4, §D.5 |

**On #4 (the one real accuracy cost in v1):** first-order upwind has numerical diffusion `~½‖u‖Δx`, so a
crisp interface smears over `O(Δx/‖u‖)` ticks. This is monotone (no spurious oscillation/over-shoot) — it
errs toward *too smooth*, never unstable. The slope-limiter recovers 2nd-order on smooth regions while
staying monotone at the edge; the label-sharpening keeps species crisp. Both are deferred to stage 4 and
gated by test 16 so v1 ships stable-but-soft and sharpens later without a model change.

---

## §I-bis — Added acceptance tests from the red-team
13. **Over-compression soak:** force a 2×-overfull cell; it must relax to `≤ m_max` without `c_s`-CFL
    blow-up, energy bounded (validates §G.1b + §C.5).
14. **Free-surface transmission:** a pressure pulse in a water column reaching the air interface must not
    hard-reflect; surface displaces and damps (validates §B.1 absorb, no artificial bounce).
15. **Vacuum stability:** drain a cell to `m→0` while a neighbor pressure-pushes it; `‖u‖` stays `≤ Δx/dt`,
    no NaN/Inf (validates §D.4 guards).
16. **Sharp-interface sharpness:** advect a crisp hot/cold (and species) edge `N` cells; measure blur width.
    v1: monotone, ≤ ~1-cell smear/transit acceptable; stage-4: edge stays within 1 cell (limiter+sharpen).

---

*Next: user review of this document → `writing-plans` (staged, TDD, subagent-driven) on `rebuild`.*
