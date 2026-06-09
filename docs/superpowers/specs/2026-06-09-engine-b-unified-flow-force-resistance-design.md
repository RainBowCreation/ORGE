# Engine-B — Unified Flow / Force / Resistance law + anti-drift apparatus (design)

**Date:** 2026-06-09 · **Status:** PROPOSED (brainstormed with the user 2026-06-09; supersedes the
reverted `p_surf` head; ratifies `NOTE-B`'s ρ-aware `p_dyn`; specifies the granular extension).
**Track:** `rebuild` (parent `/home/claude/ORGE-B` ↔ engine `/home/claude/ORGE-B/ORGE-ENGINE`).

> Read order: `00-MASTER-RULES.md` → `2026-06-07-engine-b-CANONICAL-pipeline.md` (LAW) →
> `…-vector-map-decomposition-design.md` **§8** → `…-unified-formula.md` §B.1/§C.5/§J.5 → **this doc**.
> Spec wins over code. **This doc is written to RESIST drift** (see §6) — the invariants ARE the spec.

This closes the flow/force model the way the 2026-06-09 brainstorm derived it: **one force vector, one
resistance axis, one resolver.** Solid, sand, lava, and water are the *same rule* — they differ only by a
material resistance, on a continuum. It also pins the **anti-drift apparatus** (§6) so the next
implementation does not become "something else."

---

## §1 — THE LAW (one sentence, then the parts)

> Every cell movement is the **same push request**: a single resolved force `F⃗ = −∇P + g⃗ + advection`
> meets a single **resistance `R`** of the interface, and the outcome (flow / swap / push / hold) is a point
> on the resistance continuum. There is **ONE resolver**: `P` is a *persisted* field that relaxes over ticks
> (artificial compressibility); `F` is its resolved gradient computed in the **same pass**. There is **no
> second "force" resolve** and **no within-tick pressure solve**.

The three things that are *one* thing:

| concept | what it is | NOT |
|---|---|---|
| `P` (pressure) | persisted scalar `p_dyn + p_eos`; relaxes over ticks via `dp/dt = −c²·ρ·divU` | NOT re-solved each tick; NOT a global Σ; NOT an EOS band (`max==default` stands) |
| `F⃗` (force) | the netted 6-face gradient of `P`, plus gravity/advection, **same pass** | NOT a separate resolve step; NOT absolute `P` |
| `R` (resistance) | the interface's threshold to move | NOT a global constant; NOT a `χ` gate |

---

## §2 — The resistance continuum (solid = sand = lava = water, one rule)

`R` is a property of the interface, ranging continuously. **Three orthogonal sub-axes** — do not conflate
them (conflating viscosity with yield is a known error, see FORK-6):

| sub-axis | governs | symbol | solid | sand | lava | water |
|---|---|---|---|---|---|---|
| **yield_stress** | the THRESHOLD: hold / flow / burst | `τ_y` | ∞ | finite (stacks) | ≈0 | ≈0 |
| **cohesion / pair-resistance** | the immiscible SWAP barrier | `R_pair` | n/a | — | small | small |
| **viscosity** | the RATE once moving (orthogonal) | `μ→λ` | — | high | high | low |

The outcome of one push request, by where the target sits:

| target (`X` in `[A, X, X, WH]`) | `R` | result |
|---|---|---|
| stone | `τ_y = ∞`, **stacks** | `F < Σ τ_y` → **HOLDS / reinforces** (silo/arch shields below) |
| sand | `τ_y` finite, **stacks** | `F < Σ τ_y` → holds (looks solid); `F > Σ τ_y` → **BURSTS** → chain shifts |
| lava (immiscible) | `τ_y ≈ 0` | flows immediately, **sharp** interface (one species/cell); §8.5 lateral |
| water (same species) | `τ_y ≈ 0` | flows + **merges** (same-species level) |

**Key:** `yield_stress` decides IF it moves; `viscosity` decides HOW FAST; `cohesion` decides the immiscible
swap. A high-viscosity Newtonian fluid (thick honey) has `τ_y≈0` ⇒ it **creeps, never holds** — only
`yield_stress` produces hold-then-burst. (FORK-6.)

---

## §3 — `p_dyn` must be ρ-aware (the SCENE-1 root fix; `p_surf` is REVERTED)

**Confirmed root cause (NOTE-B, user-verified against §C.5/§J.5):** a resting floor cell's **weight `m·g`**
reflects off the floor into pressure ⇒ the pressure response is **mass/density-dependent**. The bug was two
mass-cancelling defects in T2's artificial compressibility:

- `engine_b.hpp:193` `e.ugy = uy − dt*G.g` — gravity as **acceleration** (mass cancels; 1000 and 500 get
  the same downward velocity).
- `engine_b.hpp:90` `p_ac_scale = 2000` (a **constant**) used at `:1062`
  `pNew = p − head_relax·p_ac_scale·incompr·divU` — the constant sits where the law `dp/dt = −c²·ρ·divU`
  needs the cell's **ρ**.

⇒ both cells build identical `p` ⇒ no horizontal gradient ⇒ SCENE-1 frozen at `1000|500`.

**The fix (one field, no new field):** restore the **ρ-weighting** —
`pNew = p − head_relax · (c²·ρ_cell) · incompr · divU`, `ρ_cell = m/V`. Then a 1000-cell builds 2× the `p`
of a 500-cell at equal height ⇒ gradient ⇒ the **existing same-species flux** levels them to `750|750`. The
same corrected `p_dyn` is the driver for the §4 lateral water→air move — **§8.5 needs no pressure term of
its own.**

> **`p_surf` is reverted and FORBIDDEN.** It was the right *value* (`ρg·h`) bolted on as a **4th field**,
> double-counting the head this section already produces. Adding it back is a build failure (§6, INV-3).

**Guard rail (NOTE-B):** ρ-weighting risks reviving the old `maxP→∞` runaway / per-species air leak. The
walled column MUST settle to `maxP = ρg·depth` exact (INV-2). If it rings, **ESCALATE** (FORK-4) — do not
re-patch with a damping field.

---

## §4 — §8.5 lateral lowest-`P` mover: the eligibility predicate (kills the stone-wall trap)

A full liquid cell whose `P` exceeds a horizontally reachable lower-`P` neighbour drives mass sideways,
**consuming the lowest-`P` reachable cell.** But "lowest-`P`" alone is a trap: at rest a **stone wall** and
**air** both read `P≈0`, so a naive `P`-min would try to eat the rock. Consumption is decided by a
**different axis** (compressibility + yield), not by `P`:

```
eligible(neighbour)  =  (χ > 0  OR  vacuum)        # can ABSORB the displaced mass (sink)
                    AND  NOT yield_locked            # not a load-bearing solid (§8.3 wall)
rank the ELIGIBLE neighbours by P; consume the lowest.   # P only orders the eligible set
```

- air/vacuum → eligible (the real sink). solid → **never eligible** (incompressible + load-bearing): it is a
  *force gate*, never a *mass sink*. same/diff-species liquid → flow/swap, not consumed.
- "reachable" = **1-hop only** (the 6 immediate faces); never transitive (transitive = non-local). A buried
  pocket two cells away is reached by the flux walking there over ticks (signal speed ≤ 1 cell/tick).
- **Conservation corollary** (the real reason solids are excluded): a consumed cell must *absorb or relocate*
  its contents. Air can (compress, or route to *its* lowest-`P` neighbour). A rock cannot — so excluding it
  is structural, not cosmetic.

Same antisymmetric snapshot pass; GPU-safe gather; **flux-XOR-swap** with the vertical swap; yield gate
applies sideways (a wall bears the lateral load and blocks). Conserve grand + per-species exact; honour the
§8.7 min_mass gate (cross-species seed needs `f ≥ min`; never leave `0 < m < min`).

---

## §5 — Granular push: the atomic chain + stacked yield (DEFERRED, but specified now)

When `yield_stress` becomes finite (currently ∞ for all terrain ⇒ this is dormant), the SAME chain carries
it. **DEC-4 still defers the trigger; this section pins the shape so the eventual build can't drift.**

**The atomic chain (`[A, S, S, WH]`):** the push request propagates through yielded solids (each becomes a
flow link) toward a sink. Two outcomes, both conservative — **never destroys a block**:
- **completes** (every link clears its stacked yield, terminal cell is a compressible/vacuum sink): the whole
  line shifts one cell toward the sink — leftmost solid pushed into the air (floating is fine), water leaks
  one cell, the air is consumed. Permutation ⇒ mass exact.
- **dead-ends** (a link won't yield and isn't a sink): the **whole push is rejected**, nothing moves.

**Yield STACKS in the push direction (so a thicker wall resists more):** change §8.3's undiminished
`passes F_in` to
```
F_out = F_in − yield_stress        # each link consumes its yield to move
⇒ the chain bursts only when  F > Σ(yield along the chain to the sink)
```
A 1-thick wall bursts at `F > τ_y`; a 2-thick at `F > 2·τ_y`; identical blocks now genuinely reinforce.

**The yield gate runs POST-RESOLVE, on the netted force — NOT on absolute `P`.** A solid buried deep in
balanced water has huge `P` but ~0 **net** force ⇒ must NOT yield. Only an imbalance (water one side, air the
other = a dam) gives a net force. The netted imbalance only exists after the 6 faces are summed ⇒ the gate is
a DECRYPT-side test on the resolved force, not the ENCRYPT Bingham term (which sees only gravity+external).

**Scope chosen (user, 2026-06-09): brittle block-push only.** A scalar `F > Σ τ_y` threshold (dam-burst,
single-cell push, floating allowed) — NOT angle-of-repose / Mohr-Coulomb shear (that is a later module).

---

## §6 — ANTI-DRIFT APPARATUS (this is why the doc exists in this form)

Prose specs drift because the reward is "tests green," the agent has the doc not the model, and drifted code
is a tractor beam. Four structural teeth — **the invariants are the spec; the prose only explains them.**

### §6.1 — Frozen symbol surface (adding a symbol = build failure, INV-3)
Persisted per-cell fields: **`{ m, T, u=(ux,uy,uz), s, p_dyn }`** — nothing else.
Globals: **`{ K, γ, α, T_ref, k_scale, λ_scale, head_relax, vel_damp, p_ac_scale→(c²·ρ form), swap_kv, swap_kc }`**.
**FORBIDDEN tokens** (CI greps the diff; any hit fails the build): `p_surf`, `c_head`, `c_head_mob`, any new
`*_head` field, any new Globals constant, any `chi`/`χ` swap gate, any `swap_threshold` global, any
`Σ mass above` / column sweep. Adding a field/global requires a **spec change first** (this file), never an
ad-hoc term. *This makes invention harder than escalation — the whole point.*

### §6.2 — Property / invariant tests (ungameable; real LUT only)
Each law is pinned by a property test, not an end-state. To fake these you must implement the real physics.

| id | invariant | pins | kills drift |
|---|---|---|---|
| INV-1 | two same-height cells `m1>m2` ⇒ `p_dyn(1)/p_dyn(2) ≈ m1/m2` | §3 ρ-weighting | `p_ac_scale` constant |
| INV-2 | walled column: `p_dyn(depth d) ≈ ρg·d` (slope `ρg`), settles, no ring | §J.5 + guard | non-hydrostatic `p`, runaway |
| INV-3 | persisted-field set + globals == the §6.1 list (grep) | symbol surface | `p_surf`/new-field invention |
| INV-4 | a swap step is a **bit-exact** mass permutation; per-species exact | §8.2 | fabricating cross-species moves |
| INV-5 | liquid next to ONLY {solid, far-air} does **not** consume the solid; consumes air via the chain | §4 eligibility | naive lowest-`P` stone-eat |
| INV-6 | hydrostatic column with correct `p` does **not** move (`Δm≈0` FP) | §J.5 fixed point | spurious rest flow |
| INV-7 | the step is **one** antisymmetric snapshot pass (shape assert) | one-resolver | 3-local-passes / two-step |
| INV-8 | (when `τ_y` finite) 1-thick bursts at `F>τ_y`, 2-thick at `F>2τ_y`, never `F<τ_y` | §5 stacking | undiminished `passes F_in` |

Existing GREEN that must STAY green for the RIGHT reason (assert MOVEMENT, not "bounded"): flat-pool-level,
connected-leveling, hydrostatic-rest, yield_gate, eos, swap_resistance, conduction, conservation_levels.

### §6.3 — Pre-answered forks (make the silent forks loud)
The places an implementer *will* be tempted, decided in advance:

- **FORK-1 — "SCENE-1 won't level":** fix the ρ-weighting of `p_dyn` (§3). DON'T add a fill/free-surface head
  field (it double-counts; it's the reverted `p_surf`).
- **FORK-2 — "lowest-`P` picked a solid/wall":** apply the §4 eligibility predicate BEFORE ranking `P`.
  DON'T rank raw `P`.
- **FORK-3 — "rest pressure is 0 so there's no gradient":** the gradient comes from ρ-weighted `p_dyn` over
  ticks. DON'T widen the EOS band; DON'T touch `max==default`.
- **FORK-4 — "it rings / converges too slowly":** that is the artificial-compressibility tradeoff. Tune
  `head_relax`/`vel_damp` within range; if it can't settle, **ESCALATE** — a switch to a two-step
  predictor+projection is a real architecture change needing sign-off. DON'T silently add a damping field.
- **FORK-5 — "an existing test went red":** TRIAGE against these invariants (stale vs regression, per
  00-MASTER-RULES §🧨). Don't game it; don't blindly satisfy it.
- **FORK-6 — "make it hold like a solid":** use `yield_stress` (threshold), NOT high `viscosity` (rate).
  Viscosity never produces hold-then-burst.

### §6.4 — Independent oracle, every task
After implementation, a separate agent re-derives the observable **from this spec only, never reading the
implementation**, writes its **own** scratch test, and checks **why** it passes (not that it passes). Standing,
not reactive. This is the only thing that catches green-for-the-wrong-reason.

---

## §7 — Decisions log (rejected alternatives + WHY — so the next agent inherits the model, not just the law)

- **DEC-A — One resolver, artificial compressibility.** `P` relaxes over ticks (`dp/dt=−c²ρ·divU`); `F=∇P`
  same pass. REJECTED the classical **two-step predictor + pressure-Poisson projection** — it needs a global
  iterative solve (non-local), violating GPU-local single-pass. Cost accepted: slower convergence (FORK-4).
- **DEC-B — ρ-aware `p_dyn`, not `p_surf`.** REJECTED the free-surface head field: right value (`ρg·h`),
  wrong place (4th field, double-counts the head `p_dyn` already builds). Fix the source. (§3)
- **DEC-C — eligibility predicate, not naive lowest-`P`.** REJECTED ranking raw `P`: a rest-state stone wall
  ties air at `P≈0`; consumption is the compressibility+yield axis, not `P`. (§4)
- **DEC-D — stacked yield `passes F_in − yield`.** REJECTED §8.3's undiminished `passes F_in`: identical
  blocks wouldn't reinforce; a thick dam must out-resist a thin one. (§5)
- **DEC-E — yield gate post-RESOLVE on netted force.** REJECTED yielding on absolute `P`: a balanced deep
  solid has high `P`, ~0 net force, must not yield. (§5)
- **DEC-F — brittle block-push scope.** REJECTED angle-of-repose/Mohr-Coulomb shear for v1 (user) — later
  module. (§5)
- **Carried:** `max==default` (no EOS band); force-difference swap by **pair resistance** not `swap_threshold`
  (T4); no `χ` swap gate; min_mass cohesion §8.7; antisymmetric ⇒ conservation.

---

## §8 — What ships now vs deferred

| | now (this spec) | deferred |
|---|---|---|
| §3 ρ-aware `p_dyn` | **YES** — fixes SCENE-1 via existing flux, no new field | — |
| §4 §8.5 lateral mover + eligibility | **YES** — SCENE-2 dam-break / pool→air spread | transitive multi-hop (stays over-ticks) |
| §2 resistance continuum | **YES** as the framing; water/lava/air live | — |
| §5 granular stacked yield + post-resolve gate | **SPEC ONLY** (`τ_y=∞` dormant) | the trigger (DEC-4); shear/repose |
| §6 anti-drift apparatus | **YES** — INV-1..8, frozen symbols, forks, oracle | INV-8 active only with finite `τ_y` |

**Acceptance gate:** §6.2 invariants GREEN on the real LUT + the in-game re-audit (leveling, pool→air spread,
lava sinks, no residue). The headless invariants are necessary; the in-game audit is the final oracle.

---

*Next: `superpowers:writing-plans` → a staged, TDD, subagent-driven plan that implements §3 then §4, each
gated by its INV-* property tests + the independent oracle, on `rebuild`.*
