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

One function, zero neighbor reads, pure map (GPU-ideal). Gravity and pressure **aim** the vector; thermal
sets its **amplitude** — exactly the user's rule.

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

### B.3 Direction `w` (m/s — aimed by motion, gravity, pressure)
Gravity is applied as a body impulse to the velocity **once per tick, here** (it then both aims the vector
and seeds Decrypt's momentum — never re-applied, so no double count):
```
u_g = u + dt·g·ĝ_down                 # gravity body impulse (uniform accel a=g, mass-independent)
w_x = u_g,x
w_z = u_g,z
w_y = u_g,y + dt·( p/(ρ·Δy) )         # = u_y + dt·(p/(ρΔy) − g) ; gauge pressure pushes up vs gravity
```
Own state can only resolve pressure's direction **along gravity** (buoyancy). Lateral pressure spreading is
not faked here — it emerges in Resolve (§C.2). A still cell with `p/(ρΔy) = g` has `w = 0` and does not move
(hydrostatic). `u_g` is the gravity-updated velocity that Decrypt (§D.2) starts its momentum from.

### B.4 The vector + viscous drag
```
E = ρ · h · w / (1 + dt·λ)           # λ = μ/(ρ·Δx²)  → dt·λ dimensionless (units pinned, §G.3)
```
- **Frozen material** (`μ → ∞` or absent): `λ → ∞` ⇒ the advective vector `E → 0`. The cell still
  participates in conduction (§C.3, which does not use `E`). This is design-law **L2** with no branch.
- A **light** cell (small `ρ`) emits a **weak** `E`; it cannot overpower a heavy cell's strong `E` in
  Resolve. *This is the structural fix for the air-pushes-water bug — by construction, not by a density gate.*

Also exposed to Resolve (read from the stored partition, not re-derived): `ρ`, `p`, `Φ = c·T`, `u`, `s`.

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
species: the donor's `s` rides with ṁ (commit in §D.3)
```
Plus the **pressure-gradient kick** (this is "pressure has direction", realized across the face high→low):
```
p⃗_pres = (p_i − p_j) · A · dt · n̂                  # momentum kick i receives; antisymmetric
```

### C.3 Diffusive channel — conduction, mass-free (antisymmetric; the unified `q`, L8)
Thermal is amplitude; Resolve turns the amplitude **difference** into a direction:
```
q = k_face · (Φ_i − Φ_j) · (A/Δx) · dt             # Φ = c·T ; k_face = harmonic mean(k_i,k_j)
```
Works from rest (`u=0, W=0`) — pure Fourier conduction, no mass motion. This is the entire replacement for
`orge_kernel.hpp`; there is **no separate conduction pass**.

### C.4 Per-cell accumulation (the "new vector map result")
Summing the six faces gives cell `i` its resolved exchange:
```
Δm_i = Σ (±ṁ)                          # net mass            (signed: + into i)
Δp⃗_i = Σ (±p⃗_adv ± p⃗_pres)            # net momentum exchange (gravity already in u_g, NOT re-added)
ΔE_i = Σ (±E_adv ± q)                   # net energy (advective + diffusive)
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

### D.4 Species commit under L6 sharp (handoff Q6)
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
   isotropic pressure resolved along gravity in `w`, laterally in Resolve; thermal = amplitude `h`. ✔
2. **Pass-2 antisymmetric face flux** → §C.1–C.3, proven antisymmetric in §E. ✔
3. **The energy decomposition (biggest)** → §C.2 splits the face transfer into advective (`ṁ·h`) vs
   diffusive (`q`); §D splits advective into `Δm` / `Δp⃗→Δu` / `ΔE_th→ΔT`. Conserves mass **and** energy. ✔
4. **Absorb vs reflect** → §C.5, emergent from the EOS, no branch. ✔
5. **Advective mass amount + hard wall** → §C.2 (`ṁ`), §C.5 capacity clamp + `^γ` wall. ✔
6. **Species commit under L6** → §D.4: cargo on `ṁ`, relabel/merge, no comparison. ✔
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
- **D2 — pressure aims the vector along gravity** in Encrypt (buoyancy/hydrostatic, §B.3) and acts
  laterally via the high→low kick in Resolve (§C.2). Thermal stays amplitude; gravity+pressure are direction.
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

*Next: user review of this document → `writing-plans` (staged, TDD, subagent-driven) on `rebuild`.*
