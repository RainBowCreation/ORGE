> # ⛔ PARTIALLY SUPERSEDED 2026-06-10 by [`2026-06-10-engine-b-unified-spec-v4.md`](2026-06-10-engine-b-unified-spec-v4.md):
> **§8 (force model) and §3's sub-min→VACUUM rule are superseded** (the latter DELETES mass — audit finding;
> v4 §6.4 flags-and-drains instead). The un-mixed-maps decomposition (§1) and staging idea remain historical
> context. See `../notes/2026-06-10-physics-math-audit-report.md`.

# Engine-B — Vector-Map Decomposition & Phase-Split Staging (design)

**Date:** 2026-06-07 · **Status:** RATIFIED this session (the user's "last spec talk" — confirmed the
decomposition + the two staging forks below). **Track:** `rebuild` (parent `/home/claude/ORGE-B` ↔ engine
`/home/claude/ORGE-B/ORGE-ENGINE`).

> **Read order:** `2026-06-07-engine-b-CANONICAL-pipeline.md` (THE LAW) → this doc (pins the one open
> delta + staging) → `2026-06-04-engine-b-unified-formula.md` (the per-channel MATH, unchanged) →
> `2026-06-04-engine-b-velocity-field-design.md` (the model). **Spec wins over code, always.**

This document closes the single design gap the re-plan handoff flagged: **the concrete decomposition of the
"multiple un-mixed vector maps,"** plus the staging the implementation plan will follow. Everything else is
already ratified; this exists so the staged plan can be ratified in **one pass**.

---

## §1 — The decomposition (the one delta vs 2026-06-04)

The 2026-06-04 engine wrote **one bundled vector** `E = ρ·h·w` to the grid; RESOLVE then had to *un-bundle*
it (`w = E·(1+dt·λ)/(ρ·h)`). That bundling is what made the engine un-auditable — you could not touch mass
transport without touching temperature. The fix is **representation only**: ENCRYPT writes the **separate
primitives** RESOLVE needs; no bundling, no recovery step; the resolver stays.

**The three un-mixed maps ENCRYPT writes (per cell, OWN STATE ONLY — zero neighbour reads, GPU-ideal):**

| # | Map | Symbol | Formula (own state) | Spec |
|---|---|---|---|---|
| ① | Mechanical drive | `w⃗` (vec) | `u_g = u + dt·g·ĝ_down`, viscosity-damped `/(1+dt·λ)`, `λ=μ/(ρΔx²)`; Bingham `yield_stress` term on net force (gravity+external only — see §4) | §B.3–B.4 |
| ② | Pressure modifier | `p` (scalar) | EOS `p = f(m,T; m_min, m_0, m_max)`; `p = 0` at rest density (free surface) | §B.1 |
| ③ | Thermal | `h`, `T` | `h = c·T + p/ρ + ½‖u‖²` (advected amplitude) **and** `T` (conduction potential) | §B.2, §C.3 |

Also exposed (read from the stored partition, not re-derived) for the resolver: `ρ, u_g, s`.

**Persisted state is unchanged from 2026-06-04:** only `u = (ux,uy,uz)` is new on disk (`+3×float16`,
defaults 0 on old-region load); `m, T, s` already persist. **All three maps are derived transient each
tick** — nothing extra is stored.

**RESOLVE — the ONE cross-cell step (antisymmetric face flux from a single pre-step snapshot)** nets each
channel **separately** over the 6 faces (math identical to §C, just no `E`-unbundle):

```
W       = ½(w_i + w_j)·n̂                       donor = upwind(W)
ṁ       = ρ_donor · W · A · dt                  # mass     (channel ①)
p⃗_adv   = ṁ · u_g,donor                         # advected momentum
Δp⃗_pres = − p_face · A · dt · n̂_out             # PRESSURE AS A FLUX (channel ②) — NOT a local ∇p kick
E_adv   = ṁ · h_donor                           # advected enthalpy (channel ③)
q       = k_face · (T_i − T_j) · (A/Δx) · dt     # conduction        (channel ③, mass-free)
→ Δm_i = Σ(±ṁ) ;  Δp⃗_i = Σ(±p⃗_adv + Δp⃗_pres) ;  ΔE_i = Σ(±E_adv ± q)
```

This is where **pressure-at-depth, hydrostatic head, leveling, buoyancy, and conduction emerge** (the
§J.5 hydrostatic-balance proof). A purely-local per-cell EOS cannot make pressure-at-depth — only the
resolver can. Conservation lives here and only here (antisymmetric ⇒ grand mass + per-species mass + grand
energy exact). **Pressure is a flux, never a local ∇p-into-velocity term** — that local-EOS error is the
stale `force_advect.hpp` drift that killed in-game leveling.

**DECRYPT (per cell, own state only)** re-partitions `(Δm_i, Δp⃗_i, ΔE_i)` → new `m, u, T`:
`m' = m + Δm`; `u' = (m·u_g + Δp⃗)/m'`; `ΔE_th → T'`; CFL + vacuum guards; **drained cells relabel →
VACUUM + restore min_mass cohesion** (§3).

**This is not new physics.** Term-for-term, every RESOLVE flux equals its 2026-06-04 §C counterpart
(proven §J.2). The decomposition changes the *bookkeeping* (separate auditable buffers instead of one
`E` product), not the *arithmetic*.

---

## §2 — Cross-species reorder = force-difference threshold swap (GPU-safe)

Two incompressible cells (e.g. lava above water, both `χ≈0`) cannot exchange via the §C flux — the stiff
`^γ` wall reflects it. The canonical spec replaces the **stale `chi(lower)≥0.5` gate** (audit #2: lava
won't sink) with a **force-difference threshold swap**:

- For a vertical pair (upper `i`, lower `j`), compare net downward drive; **swap whole-cell contents when
  the upper's buoyant drive exceeds the PAIR'S RESISTANCE** (heavy-on-light,
  `(ρ_i − ρ_j)·g·V > resistance`). **The barrier is the pair's material resistance (viscosity / cohesion),
  NOT a global `swap_threshold` constant** — refined §8 (the global constant is drift). The buoyant drive is
  the **local** density difference; **overburden cancels** (Archimedes — depth-independent; see §8).
- A swap is a **pure permutation** ⇒ grand + per-species mass exact by construction.
- The swap is the **discrete realization of the same vertical force** that fluxes movable media: the force
  *flows* where mass can move continuously and *swaps* where two full immiscible incompressible cells block
  the flux. One driver, two outcomes, one resistance gate (§8).

**GPU-safe formulation (no scatter, no atomics)** — the swap is the one piece that *looks* like a scatter,
so it is written as a **gather**, identical in shape to the flux:

1. each cell reads its vertical neighbour from the read-only snapshot;
2. both independently reach the **same** deterministic swap decision (force-difference > threshold);
3. each thread writes only **its own** cell's new contents;
4. **one-swap-per-cell-per-tick** tie-break (a cell may not swap up *and* down in the same tick);
5. **flux-XOR-swap per face** — a face either fluxes or swaps, never both (no double-moved mass); a
   swapping cell does no advective flux on its other faces that tick.

The swap lives **inside the RESOLVE kernel** (same snapshot, no extra pass). It is the highest-risk
conservation code in the engine and gets **adversarial code review** before it ships.

---

## §3 — DECRYPT cleanup (fixes audit #3: residue + ghost cells)

DECRYPT must, per cell:

- relabel any cell drained below the mass floor (`m' < max(ε, min_mass)`) to **VACUUM (matIx 0)** with
  `u' = 0` — kills 0.0-mass ghost cells still labelled water/lava, and stops the Java `inject=orge:air`
  queue flood (the engine was leaving 0-mass labelled cells that Java tried to re-air forever);
- **restore min_mass cohesion** (sub-min residue does not linger as a labelled fragment);
- apply the **CFL speed cap** `‖u'‖ ≤ Δx/dt` and the vacuum-velocity guard (§D.4);
- species commit (§D.5): same-species merge; full-cell relabel on different-species refill.

---

## §4 — DEFERRED / NOT YET IMPLEMENTED (loud note — Q1 = A)

> **⚠ Solid-under-load static yield is NOT implemented in the fluid core, by design.**
>
> ENCRYPT applies the Bingham `yield_stress` term against **gravity + external force only**. The dominant
> real load on a buried solid — **pressure-at-depth** — is computed in RESOLVE, *after* ENCRYPT runs, so
> ENCRYPT cannot yield against it. Therefore in the fluid core, `yield_stress` is present as a **data axis
> + a branchless line, but it is `0` for all fluids ⇒ a no-op.**
>
> **Not implemented (a later "granular / solids" stage owns it):** static yield against post-RESOLVE
> load — sand holding an angle-of-repose then avalanching, dams bursting when head pressure exceeds the toe
> block's `yield_stress`, landslides / structural collapse. That needs a yield gate that runs **after the
> resolver** (likely a DECRYPT-side gate on the momentum→velocity step), which is unratified and out of
> scope here.
>
> This note is mirrored at the code seam (a `// DEFERRED:` marker on the ENCRYPT Bingham line) and in
> memory `[[engine-b-deferred-solid-yield]]`.

---

## §5 — Staging (Q2 = A: split by pipeline phase)

Three stages, **phase-ordered**: ENCRYPT → RESOLVE → DECRYPT. Because RESOLVE's output is only observable
through DECRYPT, the build carries a **runnable, conserving `step_world` from stage 1** (a no-op resolve +
a minimal re-partition decrypt), and later stages fill in and harden each phase. **Every stage ends with an
end-to-end `step_world` acceptance test on the REAL material LUT** (not per-phase units only) — this is the
guard against the "per-pass green while the pipeline is broken" trap that burned the last build.

| Stage | Phase | Delivers | End-to-end acceptance (real LUT) |
|---|---|---|---|
| **1** | **ENCRYPT** | the 3 maps (drive/pressure/heat) per cell; `step_world` harness with **no-op RESOLVE** + **minimal pass-through DECRYPT**; persist `u`; JNI re-pointed off `force_advect.hpp` | maps assert correct values for real materials at known states; a rest scene **stays at rest**, grand + per-species mass + energy conserved, **no fabrication** |
| **2** | **RESOLVE** | full antisymmetric face flux — mass / advected momentum / **pressure flux** / advected enthalpy / **conduction `q`** — plus the **cross-species swap** (§2); minimal decrypt now interprets `(Δm,Δp⃗,ΔE)` | **water actually levels & flows horizontally** (audit #1); **lava actually sinks under water** (audit #2); buoyancy order lava<water<air; incompressible displacement; conservation soak; conduction relaxes to Fourier — **assert it MOVED**, not just "bounded" |
| **3** | **DECRYPT** | hardening — VACUUM relabel, min_mass cohesion, CFL / vacuum / species-commit guards (§3) | **no sub-min residue, no 0.0-mass ghosts** (audit #3); Java `inject=orge:air` flood gone; conservation + bounds still hold; **full in-game re-audit gate** |

Inertia/reflection (slosh, bubbles/plumes), thermal convection edge cases, interface sharpening, and perf
(spec §11 stages 2–4 / §I-bis tests 13–16) are **banked** for follow-on stages after the phase pipeline is
green and the in-game gate passes.

---

## §6 — Acceptance gate (carried verbatim from the in-game audit)

The plan's headless gate = spec **§I / §I-bis** tests run with the **REAL material JSONs**
(`core/src/main/resources/data/orge/orge/materials/*.json`), **never synthetic Materials** — synthetic
`chi=0.5` water masked the dead engine last time. Each fluid test must **assert displacement actually
happened**. The three in-game audit findings are the final gate:

1. leveling / horizontal flow works (RESOLVE pressure flux) — stage 2;
2. lava sinks under water (force-difference swap) — stage 2;
3. no sub-min residue / 0.0-mass ghosts (DECRYPT cleanup) — stage 3;
4. (don't regress) no mass fabrication, no `max_mass` overshoot.

---

## §7 — Decisions log (this session, user-ratified)

- **DEC-1 — Three un-mixed maps** (drive `w⃗`, pressure `p`, thermal `h`/`T`) replace the single bundled
  `E = ρ·h·w`. Representation change only; per-channel math = 2026-06-04 §C. (§1)
- **DEC-2 — Pressure is a RESOLVE flux**, never a local ENCRYPT ∇p-into-velocity term (the §J.5 crux). (§1)
- **DEC-3 — Cross-species reorder = force-difference threshold swap**, GPU-safe gather, inside RESOLVE,
  one-swap-per-cell + flux-XOR-swap. Replaces the stale `chi` gate. (§2)
- **DEC-4 — Q1 = A: defer solid-load yield**; `yield_stress` is a no-op data axis in the fluid core; loud
  note in spec + code seam + memory. (§4)
- **DEC-5 — Q2 = A: stage by pipeline phase** (ENCRYPT → RESOLVE → DECRYPT), each delivering a runnable
  conserving `step_world` with a real-LUT end-to-end acceptance test. (§5)
- **DEC-6 — Pressure-at-depth EMERGES via gravity + incompressible reflection over ticks**, computed in the
  ONE snapshot vector resolve — NOT a global "Σ mass above" column sum, NOT an EOS compression band.
  `max == default` stands. (§8)
- **DEC-7 — Swap barrier = the pair's material resistance (viscosity/cohesion), not a global constant**;
  the swap is the discrete branch of the one vertical force; overburden cancels (Archimedes). (§2, §8)

---

## §8 — REFINEMENT (2026-06-07 PM, user-ratified): one force vector, emergent overburden, unified gate

This section pins *how* the RESOLVE force/pressure is actually computed and gated — closing the questions
that surfaced while red-teaming the leveling/displacement model. It supersedes any earlier "overburden via
a column pre-pass" sketch. **All of it is one vector field, resolved in one snapshot pass per tick.**

### §8.1 — One force VECTOR, one pass (not a scalar, not 3 axis-steps, not a pre-pass)
- **Pressure `p` is a scalar** (isotropic — genuinely directionless); a cell legitimately reads each
  neighbour's one scalar `p`.
- **The force is the VECTOR** `F⃗ = −∇p + g⃗ + advection`, assembled from the **6-face netting** (§1's
  `Δp⃗_i = Σ(±p⃗_adv + Δp⃗_pres)`) **in one accumulation** — `Fx, Fy, Fz` fall out together. There is **no
  x-pass / y-pass / z-pass and no separate "accumulate overburden" sweep.** Each cell reads only its 6
  neighbours' scalars — never the column/row — so it stays GPU-local.

### §8.2 — Overburden EMERGES over ticks from gravity + reflection (no Σ, no band)
The bug being fixed: the live code drives the pressure flux from the **local EOS** `p`, which is `0`
everywhere at rest under `max==default` (every cell at `m_rest`) ⇒ zero gradient ⇒ no leveling. The fix is
**not** a wider EOS band (that contradicts `max==default`) and **not** a global `g·Σ(mass above)` (non-local
*and* it would wrongly transmit through load-bearing solids). Instead:
- gravity adds `m·g·dt` downward momentum to every cell each tick;
- an incompressible floor/cell cannot accept mass (`max==default` + capacity clamp) ⇒ it **reflects** that
  momentum into **pressure** (§C.5);
- that pressure raises `p`, which the *same* vector field carries up and sideways next tick;
- after ~H ticks the field self-assembles to **hydrostatic** (equal `p` at equal depth, level surfaces).

So the "force from above = X" a cell sees is just its upper neighbour's vector component **from the
snapshot** — one read, one pass, accumulation is **temporal** (over ticks), never a within-tick global scan.

### §8.3 — Force transmission is YIELD-GATED (per face, one scalar in)
Each cell takes the single incoming force scalar on a face and decides what it passes on:

| receiver on that face | passes on |
|---|---|
| **FLUID** (yield ≈ 0) | down-face: `F_in + g·m_own` (gravity accumulates) · side/up-face: `F_in` unchanged (Pascal transmits, **no weight added** — no sideways gravity) |
| **SOLID, `F_in ≤ yield_stress`** | **LOCKED — bears the load, blocks**: passes `0` downward ⇒ the cell beyond is **shielded** (silo/arch). Today every solid is terrain `yield=∞` ⇒ always blocks; finite yield later = granular (DEC-4, deferred) |
| **SOLID, `F_in > yield_stress`** | **YIELDS — pushable**: passes `F_in + g·m_own` (now part of the flow) |
| **VACUUM / free surface** | resets: `p = 0` |

A cell never counts mass; it only adds its own weight (down) or relays (sideways) the one scalar that
arrived. Vertical **accumulates** (gravity), horizontal **transmits** (Pascal) — same code, the only
difference is whether gravity is on that face's axis.

### §8.4 — Horizontal flow = the DIFFERENCE; the gate works sideways
Horizontally there is no gravity term, so the net sideways force is `p_left − p_right` (flow toward lower
`p`); uniform pressure ⇒ faces cancel ⇒ no flow. The yield gate applies sideways too: a **wall** between two
pools bears the lateral load and blocks ⇒ the pools do not level through it. Communicating vessels / U-tubes
level because the **vertical** accumulation builds high `p` at the tall column's base and the **horizontal**
transmission carries it (unchanged) to the short base, whose lower `p` is then pushed up — all one field.

### §8.5 — "Lightest = lowest force vector" is what gets consumed
In a displacement, the cell **destroyed/consumed is the lowest-`p` (lowest force-vector) cell the flow can
REACH**, in **any** of the 6 directions — not "the gas," not gated by `max_mass`. Air usually has the
lowest `p` (tiny own-weight, often exposed), but a buried air bubble with high overburden is **not** consumed
while a lower-`p` cell is reachable. Conservation: only that lowest-`p` compressible/vacuum cell is consumed;
everything else permutes/shifts.

### §8.6 — The unified gate (flow / swap / stay) — one force, one resistance axis
```
fluid FLOWS   when   ∇p force   > 0           (no barrier — continuous flux)
cells SWAP    when   buoyant F  > pair resistance   (viscosity/cohesion)   ← replaces global swap_threshold
solid MOVES   when   F_above    > yield_stress       (granular — DEFERRED, DEC-4)
```
Same vertical force vector everywhere; the **resistance axis** (viscosity / cohesion / yield_stress) decides
the outcome. The swap is the discrete branch taken when two full immiscible incompressible cells block the
continuous flux; its driver is the **local** buoyant force `(ρ_up − ρ_low)·g·V` (overburden cancels —
Archimedes, depth-independent), and its barrier is the **pair's resistance**, not a constant.

### §8.7 — min_mass flow gate (the cohesion rule, user worked-example confirmed)
A same-species flow of amount `f` from donor `D` to receiver `R` is allowed **iff**
`R+f ≤ max` **AND** (`D−f == 0` *or* `D−f ≥ min`). Cross-species requires `f ≥ min`. Flows are **not**
quantized to `min`; the **only** ban is leaving or creating a cell at `0 < mass < min`. Worked: water
`min125/max1000`, a 130 drop onto an 875 cell — room 125, sending 125 leaves the donor at 5 (`<min`) ✗,
sending 130 overfills to 1005 ✗ ⇒ **blocked**; needs ≥ 250 to transfer 125 and keep ≥ 125 → `[1000, 125]`.

---

*Next: `superpowers:writing-plans` → the staged, TDD, subagent-driven implementation plan on `rebuild`.*
