# Engine-B — Unified Flow / Force / Resistance law + anti-drift apparatus (design)

**Date:** 2026-06-09 · **Status:** PROPOSED **v2** (brainstormed 2026-06-09; **drift-audited** `wmr21mnjb`
→ §9 hardening folded in; the audit found §6 *necessary but not sufficient* and caught a LIVE re-drift, §9.0).
Supersedes the reverted `p_surf` head; ratifies `NOTE-B`'s ρ-aware `p_dyn`; specifies the granular extension.
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

---

## §9 — DRIFT-AUDIT HARDENING (v2, 2026-06-09, from independent audit `wmr21mnjb`)

The §6 apparatus was drift-audited by 8 adversaries + a synthesizer. Verdict: **necessary but NOT
sufficient** — proven *empirically*, the live engine already embodies the canonical `p_surf` drift and would
pass all 8 invariants green. The four root gaps and their closers (these AMEND §3/§4/§5/§6 above):

### §9.0 — CRITICAL STATE CORRECTION: the engine has RE-DRIFTED (verified)
Engine HEAD `c73e0f7` ("§8.4 leveling via own-weight head") **re-introduced the reverted head** as the
**function** `own_weight_head()` (`engine_b.hpp:285` = `ρg·dx/2`), wired as the §8.4/§8.5 lateral pressure
read (`:628/:893/:894`), **decoupled from `p_dyn`**. §3's "`p_surf` is reverted" is **FALSE in the live
engine.** **PRECONDITION to §3 (build-failure if violated):** `own_weight_head()` and the entire own-weight
lateral path **MUST be deleted**; §8.4/§8.5 read **ONLY** persisted `p_dyn` (`Chunk::p`) — no
freshly-computed `ρgh`. *(Reverting `c73e0f7` itself is a user/controller decision — see handoff.)*
Extend §6.1's FORBIDDEN list **semantically**: any **function/expression** of the form `(m|mass)/V·g·(dx|h)`
or `rho*g*…` that sources a horizontal/lateral pressure read **outside** the persisted-`p` divU relaxation —
name `own_weight_head` and any `*_head(` FUNCTION form explicitly (the field-only grep was evaded).

### §9.1 — INV-1b COUPLING (the keystone — kills the whole right-value/wrong-mechanism class)
SCENE-1 `[1000|500]` real LUT: with any side-channel head zeroed, **FREEZE `p_dyn` at rest ⇒ lateral flow
VANISHES** (no leveling); with ρ-aware `p_dyn` ⇒ levels to `750|750`. Equivalently assert per-step lateral
`Δm ∝ (p_dyn_i − p_dyn_j)` directly. **No parallel head term survives this.** (Closes §6.2's "no invariant
couples leveling to `p_dyn`".)

### §9.2 — INV-7 rewritten: STRUCTURAL + SEMANTIC (was a toothless output-only "shape assert")
`force_advect.hpp`'s 3-local-passes AND a predictor+projection 2-loop split both pass an output test
(determinism ≠ single-pass). Replace with: (a) **wording** — "RESOLVE reads grid state from EXACTLY ONE
pre-pass snapshot; ZERO reads of any post-update `m/u/T/p_dyn/p` during the pass; `Fx,Fy,Fz` fall out of ONE
face-traversal accumulating BOTH advective and pressure flux per face — no separate predictor sweep, no
separate pressure-projection sweep even over the same snapshot." (b) **semantic CI grep** — `resolve_world`
reads only the frozen snapshot/encrypt buffers, writes only the `WorldAccum` delta; flag any live-grid READ
in the accumulation loop, any 2nd `snapshot_world()` in `step_world_b`, any TWO distinct face-loops both
indexing the snapshot. (c) **order-invariance test** — forward vs reverse cell iteration ⇒ BIT-IDENTICAL
output. (d) **antisymmetry** — random 2-cell pair, `Δm_i == −Δm_j` to FP.

### §9.3 — INV-1/INV-2 made MECHANISM-pinning (was fixed-point only ⇒ a static `ρg·d`/column-sum passes)
- **INV-1 → fill-fraction SWEEP** (masses {1000,750,500,250,125}, equal-height walled): `p_dyn(m)/p_dyn(ref)
  ≈ m/ref` for ALL pairs, fitted intercept ≈ 0 (kills additive/clamp constants), proving `ρ_cell=m/V`.
- **INV-2b TRANSIENT/LOCALITY** — start the column far from hydrostatic (`p_dyn=0`); `p_dyn(depth d)` reaches
  `ρg·d` **only after ≥ d ticks** (signal speed ≤ 1 cell/tick). A column-sum/static head fills **instantly**
  and FAILS; the `−c²ρ·divU` relaxation passes. (This is the spec's *defining* property, asserted nowhere
  before.) Add **scaling**: run at `H∈{4,16,32}`, settle-time grows ~linearly in `H` (not constant-time).
- **divU-determinism lock** — a sealed incompressible cell at `m=default, u=0` ⇒ `divU≡0` ⇒ `p_dyn` MUST stay
  `0` (a static `ρg·d` source wrongly injects `p` with zero divergence).
- **INV-2 "settles/no ring" QUANTIFIED** — `settled := max‖u‖ < 1e-4·Δx/dt` for 2N ticks AND
  `|p_dyn − ρg·d·V| < tol` at every depth; `rings :=` `p_dyn` non-monotone by `> tol` after first crossing
  `0.9·ρg·d`. Legal ranges numeric (`head_relax∈[0.05,0.3]`, `vel_damp∈[0,0.2]`); with `vel_damp=0` it must
  still settle within `K·H` ticks (convergence from physics, not the velocity-killer).

### §9.4 — DEFINE `c²`; add FORK-7 (closes the "`p_ac_scale=2000` survives as `c²`" door)
`c²` is the artificial sound speed² **DERIVED from EOS stiffness K** (`c²=∂p/∂ρ`, unified-formula §G.1b) —
**NOT** a new Globals constant, **NOT** `p_ac_scale` renamed. **Remove the `p_ac_scale→(c²·ρ)` blessing from
§6.1**; rename to `c2`/derive-from-`K`. Tie INV-1's fitted **absolute** slope to the K/EOS prediction `±10%`
so one calibratable constant can't satisfy both linearity AND the K-tie. **FORK-7:** "if the relax
coefficient needs a free constant, ESCALATE."

### §9.5 — §2 FORK-6 fix: viscosity is RATE, never THRESHOLD (live `R_pair=swap_kv·√visc` violates this)
Change `R_pair` to depend ONLY on **cohesion (`min_mass`) and `yield_stress`**; **demote viscosity to a
time-constant** on a *committed* swap (ticks-to-complete) so it CANNOT appear in the `>`-comparison that
decides hold-vs-swap. **Add `swap_kv` to §6.1 FORBIDDEN** (it is the viscosity-into-barrier coefficient = the
FORK-6 mechanism, same class as the banned `swap_threshold`). **INV-9** "viscosity is rate not threshold":
(a) any pair with buoyant drive `> swap_kc·min·g` MUST eventually swap regardless of viscosity (heavier
reaches bottom by 4N steps); (b) doubling viscosity on same-species cells changes only step-count to level,
never WHETHER it levels. Re-author the `swap_resistance` HIGHRES hold to credit cohesion/yield, not viscosity
(it currently cements the drift green).

### §9.6 — §6.1 → CHECKED-IN MANIFEST (the prose list had false-positives AND false-negatives)
Real globals: `{K, gamma, alpha, T_ref, g, dx, V, A, eps_mass, head_relax, vel_damp, c2(was p_ac_scale),
swap_kc}` **+ promote `CHI_COMPR_EPS` and `LEVEL_MOB`** (file-level constants that ARE leveling knobs;
`LEVEL_MOB=0.01` was an unfrozen gameable knob) — **drop `k_scale`/`λ_scale`** (not in code; they were
false-positives that break the grep on the unmodified build; note as not-yet-in-code conduction knobs). Real
persisted fields: `{matIx, T_curr, T_next, mass_kg, vx, vy, vz, p, void_ix}` with the spec↔code map
**`p_dyn≡Chunk::p`, `s≡matIx`, `m≡mass_kg`, `u≡(vx,vy,vz)`, `T≡T_curr/T_next` (double-buffer)** and an
explicit **FORBID a 2nd persisted pressure buffer**. INV-3 = `git diff` of struct-members vs the manifest
must be EMPTY; adding a symbol edits the manifest **in the same commit** (spec-change-first, mechanized).
**Transient-scratch exemption:** `CellEncrypt/CellAccum/SwapRef/resolve-local` scratch is UNFROZEN if
recomputed each tick and never persisted. *(`p_dyn≡p` matters: a `ρgh` source slipped into the existing `p`
field would evade a "new field" check — INV-1b + §9.0 semantic grep are the backstops.)*

### §9.7 — §4 eligibility pinned + lateral conservation (closes movable/viscosity alias + untested leak)
- **Eligibility keys on `χ` and the yield-lock, NEVER on `viscosity`/`movable()`** (conflating = FORK-6).
  Pin `χ>0` from `maxMass/minMass` spread per EOS (real-LUT air `1/1.2/1000`); forbid `χ≡movable`/`!isfinite(visc)`.
- **INV-5 strengthened:** ≥2 DISTINCT solid species + a **high-viscosity COMPRESSIBLE** sink (assert
  CONSUMED ⇒ χ-keying not viscosity) + an **over-max/χ=0** full neighbour (assert NOT consumed) + a rest-state
  **P-tie** (wall-`P`==air-`P`, assert air chosen ⇒ filter-before-rank). Kills the `matIx==STONE` hardcode.
- **INV-5b per-species lateral conservation:** water→air spread, per-species water AND air mass EXACT over N
  steps; air relocates to ITS OWN 1-hop lowest-`P` neighbour. **Boxed-in REJECT:** air with no eligible sink
  on all 6 faces ⇒ move REJECTED, `Δm=0`, air NOT deleted. **1-hop guard:** sink 2 cells behind one
  consumable air ⇒ this tick consumes ONLY the 1-hop neighbour.

### §9.8 — §5 granular: author the tests NOW even though dormant (silent drift detonates on first finite `τ_y`)
- **INV-8a DORMANCY GOLDEN (active TODAY):** with all `τ_y=∞`, a multi-cell step output is BIT-IDENTICAL to
  the pre-§5 determinism golden ⇒ the §5 gate is a true no-op. (§8's "dormant" row now reads "guarded by INV-8a".)
- **INV-8 swept stacking:** one drive `F` with `τ_y < F < 2τ_y` ⇒ 1-thick BURSTS, 2-thick HOLDS; measure
  `F*(n)` for `n=1,2,3`, assert `F*(2)/F*(1)∈[2±ε]`, `F*(3)/F*(1)∈[3±ε]` (kills undiminished `F_out=F_in`).
- **INV-8b/c NET-FORCE gate:** finite-`τ_y` solid in SYMMETRIC water (`P≫τ_y`, net≈0) must NOT yield; same
  block with air one side (net>`τ_y`) DOES ⇒ proves post-RESOLVE netted force, not absolute `P`; forbids the
  ENCRYPT-Bingham shortcut. **INV-8d/e atomic chain:** `[A,S,S,WALL]` ⇒ NOTHING moves, per-species exact;
  `[A,S,S,sink]` ⇒ exactly one-cell permutation shift. Reword "atomic chain" → "the same over-ticks resolver
  flux (≤1 cell/tick, §4 1-hop)" and **forbid carrying yield through `relocate_chain`**.

### §9.9 — INV-6 non-null twin + INV-4b flux-XOR-swap junction
- **INV-6 + OFF-BALANCE control** (same test): perturb one cell's `p_dyn` below hydrostatic ⇒ it MOVES the
  right way, THEN re-settles to `Δm≈0` (ungameable by a frozen "dead engine"). **Rule: every NULL invariant
  carries a non-null twin.**
- **INV-4b:** a cell with BOTH a vertical swap-partner below AND a lateral lower-`P` air neighbour in ONE step
  does EXACTLY ONE of {swap, lateral-consume} (**XOR is per-CELL**, precedence vertical-swap > vertical-flux >
  lateral-flux), per-species exact, air sink claimed by ≤ 1 consumer. **FORK-10** states this precedence.

### §9.10 — stopgap disposition + perf budget (the §1/§4 one-resolver claim coexists with a 64-deep DFS)
- **§4 over-ticks 1-hop mover REPLACES** the within-tick non-local injection DFS (`find_chain_hop`/
  `relocate_chain`, `sim_engine.hpp`, `INJ_CHAIN_MAX=64`). Until rip-out (T8) they COEXIST on DIFFERENT paths
  (`step_world_b` ≤1 cell/tick, placement not) — a KNOWN temporary violation. **No NEW within-tick transitive
  DFS** in `step_world_b`/`resolve_world`; §5 yield must NEVER carry force through those functions. Add them
  to §6.1 FORBIDDEN in a resolve/granular-yield diff context. **INV-7c:** one `step_world_b` moves any mass
  front by AT MOST 1 cell.
- **FORK-9 (perf/convergence):** O(H)-tick convergence is the accepted artificial-compressibility cost; the
  fix is NOT sub-cycling, NOT raising `head_relax` past range, NOT a non-local column jump-start — a real fix
  is two-step projection = architecture change = ESCALATE. **Add an explicit ms/cell perf ceiling to §8** so
  the noted ~100× resolver cost is a GATE, not an in-game surprise. **FORK-8 (incompr):** pin whether the
  relax coefficient is `c²·ρ_cell` alone or `·(1−χ)` and why; assert a resting air cell's `p_dyn` is strictly
  below water's by ≥ the ρ-ratio so "air = low-P sink" rests on a checkable source.

### §9.11 — §6.4 oracle upgraded to a MECHANISM checklist (observable-only agreed with `p_surf`)
The oracle MUST: (1) assert the observable on a **NEW geometry** the implementer's tests did not use (3-mass
sweep / 5-cell column); (2) compute an **analytic absolute reference** (`ρg·d` hand-calc) and assert the
value `±tol`, not just "leveled"; (3) **run the §6.1 manifest diff itself** as a mechanism check — auditable
WITHOUT reading the algorithm, catching the invented field/function a pure-observable oracle structurally
cannot. State plainly: **observable-only re-derivation is INSUFFICIENT — it agreed with `own_weight_head`.**

### §9.12 — Un-attacked surfaces (flagged for a 2nd audit pass before/with implementation)
Conduction/heat map (the spec'd `k_scale`/`λ_scale` don't exist in code); EOS §B.1 `max==default` hard-`γ`
wall vs the new ρ-aware `p_dyn` (do `p_eos` and `p_dyn` **double-count** at over-max fills?); DECRYPT
vacuum-relabel + §8.7 min_mass restore vs the lateral relocate target creating `0<m<min` fragments; the
scheduler `dt`/sub-cycle boundary (a perf fork lands here); and the §6.4 oracle's OWN independence (never
adversarially tested against a deliberately-drifted build).

---

*Next: resolve §9.0 (the live `c73e0f7` re-drift) with the user, then `superpowers:writing-plans` → a staged,
TDD, subagent-driven plan that implements §3 (ρ-aware `p_dyn`, `own_weight_head` ripped out) then §4, each
gated by its INV-* property tests (incl. the §9 keystone INV-1b) + the upgraded §6.4 oracle, on `rebuild`.*
