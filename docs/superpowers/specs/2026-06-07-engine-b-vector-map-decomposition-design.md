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
  the upper pushes down harder than the lower by more than a hysteresis threshold** (heavy-on-light,
  `≈ (ρ_i − ρ_j)·g·V > threshold`). The threshold value is **calibration** (later); the *mechanism* is
  ratified here.
- A swap is a **pure permutation** ⇒ grand + per-species mass exact by construction.

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

---

*Next: `superpowers:writing-plans` → the staged, TDD, subagent-driven implementation plan on `rebuild`.*
