> # ⛔ SUPERSEDED 2026-06-10 by [`2026-06-10-engine-b-unified-spec-v4.md`](2026-06-10-engine-b-unified-spec-v4.md)
> (user-authorized redesign off the physics/math audit `../notes/2026-06-10-physics-math-audit-report.md`).
> v4 RETIRES this doc's A+B split (single-`P` relaxation absorbs B), the `(1−χ)` factor, `own_weight_head`,
> the `swap_kv·√visc` threshold (→ cadence), and the §9.1 raw-T/v manifest. Kept below for history only.
>
> # ⚠ THIS IS NOT THE DESIGN — it is the working spec (current implementation + deviations).
> **The design is [`../DESIGN-LAW.md`](../DESIGN-LAW.md) (frozen; only the user edits it).** Read the law
> first. Where this document and the law disagree, **the law wins.** In particular, the `A + B` pressure
> split documented below (overburden + own-weight head, own-weight used only sideways) is **DEBT, not
> design** — it violates the law (one number `P`; one isotropic six-face force). It is kept here only
> because the engine cannot yet build a correct single `P`; its **exit criterion** is a real single-`P`
> solve, after which the second term is deleted and the six-face force handles every direction. Everything
> below describes *what the code does today and why*, not *what the engine should be*. Use the genuinely
> reusable parts (the anti-drift apparatus §9–§12, the resistance continuum §2, the granular-yield design
> §6) — but never copy the A+B split, or any direction-split force, into the law.

# Engine-B — Unified Flow / Force / Resistance law + anti-drift apparatus (design)

**Date:** 2026-06-09 · **Status:** PROPOSED **v3** (REVERSAL of v2 — see changelog). Documents the **A+B
hydrostatic split as LABELED DEBT** (per the frozen law — one `P`; the probe only confirms B is currently
*load-bearing*, NOT that the split is design), and deletes v2's backwards `NOTE-B`/ρ-aware-`p_dyn` premise and its
INV-1b "keystone". **Track:** `rebuild` (parent `/home/claude/ORGE-B` ↔ engine `/home/claude/ORGE-B/ORGE-ENGINE`).

> **Read order:** `handoffs/00-MASTER-RULES.md` → `2026-06-07-engine-b-CANONICAL-pipeline.md` (THE LAW) →
> `2026-06-07-engine-b-vector-map-decomposition-design.md` **§8** → `2026-06-04-engine-b-unified-formula.md`
> §B.1/§C.5/§J.5 → **this doc**. Companion: `handoffs/FORK-DECISIONS-flow-force-2026-06-09.md` (the user's
> ratified Fork 1–4 answers) and the verification appendix **§12** below (the probe numbers that ground every
> claim here). **Spec wins over code — EXCEPT the one place noted in the changelog, now resolved.**

> ### ⚠ CHANGELOG v2 → v3 (the rare case where the CODE was right and the SPEC was the bug)
> v2 prescribed making `p_dyn` "ρ-aware" (`NOTE-B`) to fix leveling, and enshrined INV-1b ("leveling must be
> driven by `p_dyn`") as its keystone — **both written from a premise that was never run against the engine.**
> An empirical probe (real LUT, engine HEAD `7f3ce82`) **disproved it**: a resting/supported liquid cell builds
> `p_dyn = 0` (`divU = 0` at rest), so `ρ·c²·0 = 0` for any ρ — the ρ-aware fix is **structurally impossible**.
> The real leveling driver is `own_weight_head` (`ρg·dx/2`), which v2 §9.0 had ordered **deleted** as "re-drift."
> v3 **REVERSES** that: ratifies the **A (overburden) + B (own-weight head) split**, **deletes** §9.0's rip-out
> and the backwards INV-1b, fixes the anti-drift premise (*every invariant runs against the engine before it is
> enshrined*), and reorganizes the whole doc around the user's **`force > resistance`** movement frame (§0).
> Genuinely-good v2 sections are kept, corrected to reference the split. After this rewrite, "spec wins" resumes.

---

## §0 — THE FRAME: the movement law (pressure builds the field; force moves mass)

**One principle — `force > resistance`. Pressure is a scalar that moves *nothing*; only a *force* (a gradient /
an imbalance / a density difference) moves mass. `yield_stress` is crossed by *force*, never by a standalone
pressure value.** Everything below is an instance of this.

| mechanism | driving FORCE | reads | depth-dependent? | RESISTANCE |
|---|---|---|---|---|
| **FLOW** | `−∇P` | `P = p_eos + A + B` | yes | ≈ 0 (fluid) |
| **PUSH / yield** | net `−∇P + g + external` | `P` | yes | `yield_stress` |
| **SWAP** (vertical immiscible reorder) | **buoyancy** `(ρ_up−ρ_low)·g·V` | **density (mass), NOT `P`** | **NO** (overburden cancels — Archimedes) | **cohesion + yield_stress** (NOT viscosity) |

- The **SWAP reads density, not `P`** → it is immune to *every* `P` subtlety: the A/B split (§3) **and** the
  checkerboard stair-step (§5). Pressure-at-depth cannot trigger a swap; only a density inversion can. *This is
  the keystone that makes §3, §5, and §8 mutually safe.*
- A pressure **imbalance** (`−∇P ≠ 0`) is a *force* → it can PUSH/yield a cell. That is PUSH, not SWAP.
- `yield_stress` gates both SWAP and PUSH (a solid `yield = ∞` never swaps, never yields). **Viscosity gates
  neither** — it only sets the *rate* once moving (§2, §8).

---

## §1 — THE LAW (one resolver; one force; one resistance axis)

> Every cell movement is the **same push request**: a single resolved force `F⃗ = −∇P + g⃗ + advection` meets a
> single **resistance `R`** of the interface, and the outcome (flow / swap / push / hold) is a point on the
> resistance continuum. There is **ONE resolver** (ENCRYPT → RESOLVE → DECRYPT — the law's ENCODE→RESOLVE→DECODE,
same three steps under this doc's older verbs; one `step_world`, one
> antisymmetric 6-face snapshot pass): `P` is a *persisted* field that relaxes over ticks (artificial
> compressibility); `F` is its resolved gradient computed in the **same pass**. **No** second "force" resolve,
> **no** within-tick pressure-Poisson solve, **no** global column scan.

| concept | what it is | NOT |
|---|---|---|
| `P` (pressure) | persisted+derived scalar `p_eos + A + B`; **A** = persisted `Chunk::p`, relaxes via `dp/dt = −head_relax·p_ac_scale·(1−χ)·divU`; **B** = `own_weight_head` derived on the fly | NOT re-solved each tick; NOT a global Σ; NOT an EOS band (`max==default` stands) |
| `F⃗` (force) | the netted 6-face gradient of `P`, plus gravity/advection, **same pass**; reads each neighbour's scalar as a **face-average `½(p_i+p_j)`** | NOT a separate resolve step; NOT absolute `P` ranked across cells |
| `R` (resistance) | the interface's threshold to move (`yield_stress` / cohesion) | NOT a global constant; NOT a `χ` gate; NOT viscosity |

---

## §2 — The resistance continuum (solid = sand = lava = water, one rule)

`R` is a property of the interface. **Three orthogonal sub-axes — do not conflate them** (conflating viscosity
with yield is FORK-6):

| sub-axis | governs | symbol | solid | sand | lava | water |
|---|---|---|---|---|---|---|
| **yield_stress** | the THRESHOLD: hold / flow / burst | `τ_y` | ∞ | finite (stacks) | ≈0 | ≈0 |
| **cohesion** (`min_mass`) | the immiscible SWAP barrier + the no-`0<m<min` rule | `R_pair` | n/a | — | small | small |
| **viscosity** | the RATE once moving (orthogonal — **never a threshold**) | `μ→λ` | — | high | high | low |

**`yield_stress` decides IF it moves; `cohesion` decides the immiscible swap; `viscosity` decides only HOW
FAST.** A high-viscosity Newtonian fluid (thick honey, `τ_y≈0`) **creeps, never holds** — only `yield_stress`
produces hold-then-burst. Heavy-on-light is Rayleigh–Taylor **unstable**: it *always* overturns eventually;
viscosity only sets the overturn speed (Earth's mantle is 10²¹ Pa·s and still convects). **A viscosity term that
can BLOCK a swap is physically wrong** — see §8 (the live engine uses one as a *bounded proxy*; that is a
labelled limitation, not the law).

---

## §3 — Hydrostatic pressure (today) = A + B — LABELED DEBT (the SCENE-1 fix; `NOTE-B`/ρ-aware-`p_dyn` is DISPROVEN)

**`P = ρg·d` decomposes EXACTLY into two terms. Both are load-bearing *today* — deleting either one now breaks the
engine (§3.2) — so the engine keeps both. But per the frozen law this split is DEBT, not design: its exit criterion
is a real single-`P` solve, after which **B is deleted** and the one `P`'s six-face gradient handles every
direction. Until then, the exact discretization is:**

```
P_hydrostatic(cell at depth d = k·dx)  =  ρg·(k·dx)   [A: OVERBURDEN — the k cells of weight above]
                                       +  ρg·(dx/2)    [B: OWN HALF — this cell's own self-weight]
```

This is the exact discretization of a hydrostatic column, **not** two redundant patches.

> **Note — the runtime *third* term (why "A+B" understates the gap to one `P`).** The force the resolver actually
> reads at an open face is `½((p_dyn+p)_i + (p_dyn+p)_j)` (`engine_b.hpp:952/:989`) — i.e. A (`= p`) **plus a
> transient `p_dyn`** (the in-pass `divU` dynamic pressure, ≈0 at rest, NOT persisted); `p_eos` is inert
> (`max==default`). So "`P = p_eos + A + B`" is a *conceptual* decomposition; the live force is `∇(p_dyn+p)` on all
> 6 faces, with B bolted on at the 3 horizontal sites. The real distance to the law's single `P` is therefore
> slightly wider than the "A+B" label alone.

- **A — overburden** = the persisted `Chunk::p`, built by the `divU` artificial-compressibility relaxation
  (`engine_b.hpp:1257`, `pNew = p − head_relax·p_ac_scale·(1−χ)·divU`). **It is NOT a column-sum** (banned: non-local
  + wrongly transmits through load-bearing solids). It is **local `divU` convergence**: 6-neighbour only,
  propagates ≤ 1 cell/tick, *relaxes to* "weight above" over ~H ticks, and correctly **stops at a solid** (a shelf
  shields the fluid below). *"Weight above" is the steady VALUE, not the operation.* **Verified** (§12): a sealed
  H=8 column locks `p_bot → 79953 / 80000` (ratio 0.9994) by t≈1500; H=16 → `159999.7 / 160000` (1.0000) by
  t≈6000; mass bit-exact; settles flat (drift +0.0000%).
- **B — own half** = `own_weight_head()` (`engine_b.hpp:285`, returns `(m/V)·g·(0.5·dx) = ρg·dx/2`), the piece
  A **structurally cannot** build (a single supported cell has no cell above to converge `divU` against, so its
  A is `0`). B is mass-weighted (a 1000 kg cell reads 2× a 500 kg cell); returns **0 for gases** (`χ>0.999`) and
  for **unsupported/free-surface** cells (cell below is not a mass-bearing wall/incompressible solid).

### §3.1 — Composition rule (violating it re-introduces the air-launch bug)
**A drives the VERTICAL/depth force AND the bulk of HORIZONTAL leveling** — A's horizontal gradient slumps any
*height* difference (verified: the connected-leveling U-tube has an all-equal 1000 kg base row, so ΔB = 0 along
it, yet it still levels ⇒ A did it, not B). **B is a NARROW patch for the one case A cannot see: a same-level,
floor-resting pair with a FILL difference** (equal overburden ⇒ A's horizontal gradient cancels ⇒ "reads 0 at
rest," so B's mass difference is the only remaining signal). **B is NEVER added to the vertical kick and NEVER
persisted into `Chunk::p`.** Safe because: (i) same-level cells share the same A, which cancels in the horizontal
gradient, leaving that *residual same-level* leveling to B's density difference; (ii) stacked cells get their
depth from A and never use B vertically. **Why B must stay out of the vertical kick:** transmitting a cell's
average head *up* would rocket the 1.2 kg air cell above the free surface (`½·ρg·dx·A·dt` on a 1.2 kg cell),
reversing leveling and collapsing the pool. (`own_weight_head` is read at exactly 3 sites, **all horizontal**:
`engine_b.hpp:633` §8.5 displacement, `:898/:899` §8.4 leveling.)

### §3.2 — The four invariants (each RUN against the engine — §12 — before being enshrined here)
This is the INV-1b lesson made structural: **no invariant ships unrun.**
1. **Deep column reads `ρg·d`** — A builds depth (✅ §12: H=8/16 lock at `ρg·H`).
2. **`[1000|500] → [750|750]`** — B levels a walled pair (✅ §12: `750.0006 | 749.9994`, `|A−B|=0.0012 kg`,
   no upward leak, 1500 kg exact).
3. **B→0 freezes flat ground** — without B, `[1000|500]` is stuck forever (✅ §12: patched build, 2 cells, tallest
   1000, never spreads). *(This is what NOTE-B got backwards.)*
4. **A→0 collapses depth** — with `p_ac_scale=0`, a sealed column's depth pressure is identically `0` at every
   depth and tick, B untouched (✅ §12: `p_bot 79953 → 0.0`, mass exact). *Proves A, not B, carries vertical depth.*

> **FORBIDDEN (re-drift backstops):** `p_surf` or any **4th** persisted pressure field; any `ρgh`/`Σ mass above`
> column-sweep feeding A; **folding B (`own_weight_head`) into the vertical kick or into `Chunk::p`**; making the
> A coefficient a free constant outside the `divU` relaxation. *Both NOTE-B (ρ-aware A) and "B-alone, delete A"
> are mirror-image mistakes — each deletes a real, verified term.*

---

## §4 — §8.5 lateral lowest-`P` mover: eligibility (kills the stone-wall trap)

A full liquid cell whose head exceeds a horizontally reachable lower neighbour drives mass sideways, **consuming
the lowest reachable sink.** "Lowest" is decided by a **different axis than absolute `P`** (a rest-state stone
wall and air both read `P≈0`):

```
eligible(neighbour)  =  (χ > 0  OR  vacuum)        # can ABSORB the displaced mass (a real sink)
                    AND  NOT yield_locked            # not a load-bearing solid (§8.3 wall)
rank the ELIGIBLE neighbours by their HEAD (own_weight_head / density), NOT by absolute Chunk::p; consume the lowest.
```

- air/vacuum → eligible (the real sink). solid → **never** eligible (incompressible + load-bearing) — a force
  gate, never a mass sink. **Conservation corollary:** a consumed cell must absorb/relocate its contents; air can
  (compress, or route to *its* lowest neighbour), a rock cannot — so excluding it is structural, not cosmetic.
- **"reachable" = 1-hop only** (the 6 faces); never transitive. A buried pocket two cells away is reached by the
  flux walking there over ticks (signal speed ≤ 1 cell/tick).
- **Ranks `own_weight_head`/density, NEVER absolute mid-column `Chunk::p`** (✅ §12 guardrail-a: `:633` ranks
  `P_L = own_weight_head`, `:898/:899` use `dP = own_weight_head(i) − own_weight_head(j)`). This is what makes the
  §5 checkerboard harmless to displacement.

Same antisymmetric snapshot pass; GPU-safe gather; **flux-XOR-swap** per face; yield gate applies sideways (a
wall bears the lateral load and blocks). Conserve grand + per-species exact; honour §8.7 (`f ≥ min` cross-species;
never *leave* a cell at `0 < m < min` — see §7).

---

## §5 — The checkerboard (odd-even) stair-step in `Chunk::p` — a NAMED artifact, harmless

The converged column profile is a **pairwise stair-step**, not a smooth `ρg·d`: adjacent vertical cell pairs
share one `p` value, so per-cell `Δp` alternates `~ρg·dx / ~0` (✅ §12: H=16 profile `0, 20000, 20000, 40000, …`;
per-cell Δp `20000, 0, 20000, 0, …`). **This is the textbook odd-even / "checkerboard" decoupling of a collocated
pressure scheme — a named numerical mode, NOT a bug.** It is recorded here so it is never re-litigated as drift.

**Why it is harmless:** the *only* decision that reads absolute persisted `p` is the open-face force, taken as the
**centered face-average `½(p_i + p_j)`** (`engine_b.hpp:952/:989`). The net force on a cell is
`½(p_{i−1} − p_{i+1})` — the **same-parity stair-step cancels**, the lumpiness lives in the gradient operator's
null space, and those face values climb smoothly. The column balances, the base is exact, **no spurious flow**.

**Guardrails (both RUN — §12):**
- **(a) Nothing ranks ABSOLUTE mid-column `p`.** Every *selection* — §8.5 consume, §8.4 level, the swap — reads
  `own_weight_head`/density/mass/height, never absolute `Chunk::p` (✅ §12 guardrail-a). So the checkerboard
  cannot cause a wrong consume/flow/swap.
- **(b) The (undamped) checkerboard is BOUNDED.** Its amplitude **saturates at a `ρg·dx ≈ 20000` ceiling** and
  never grows per interval; `p_bot` stays bit-frozen; `max‖u‖` decays ~4 orders to `~9e-5` (✅ §12 guardrail-b).

**Wall-reaction exception (bounded, benign):** the free-slip wall reaction (`engine_b.hpp:1003–1009`) is the
**single** site that reads a *one-sided absolute* `P = p_dyn + p` (a momentum force `k = P·A·dt`), not a
face-average. At the **floor** it uses the base cell's vertical `p` (correct, non-lumpy). At a **side wall** it
could feed a mid-column cell's stair-stepped `p` into a horizontal kick — but it is a **force, not a selection**,
so §5(a) stands, and it is **empirically benign**: a symmetric sealed column settles to `max‖u‖~9e-5`, and an
**asymmetric** tall column (stone wall one side, open air the other) flows-then-settles normally — `max|vx|`
spikes during the legitimate dam-break then **decays to ~0.005**, mass conserved, no stair-amplitude oscillation
(✅ §12 wall probe). **Banked refinement:** migrate this read to a gradient form, or assert side-wall
non-exposure, if a future feature needs it ironclad.

**Banked true-fix** (only if a feature ever needs a *smooth* per-depth field): Rhie–Chow interpolation or a
staggered (MAC) grid. Not needed now (no consumer reads mid-column absolute `p`).

---

## §6 — Granular push: atomic chain + stacked yield (DEFERRED, but specified)

When `yield_stress` becomes finite (currently ∞ for all terrain ⇒ dormant), the SAME chain carries it. DEC-4
defers the trigger; this pins the shape so the eventual build can't drift.

- **Atomic chain `[A,S,S,WH]`:** the push request propagates through yielded solids toward a sink. **completes**
  (every link clears its stacked yield + terminal is a compressible/vacuum sink) → the whole line shifts one cell
  toward the sink (permutation ⇒ mass exact); **dead-ends** (a link won't yield and isn't a sink) → the **whole
  push is rejected**, nothing moves. Carried by **the same ≤1-cell/tick over-ticks resolver flux (§4 1-hop)** —
  **never** a within-tick transitive DFS, and yield is **never** carried through `find_chain_hop`/`relocate_chain`.
- **Yield STACKS** in the push direction: `F_out = F_in − yield_stress` (each link consumes its yield), so a
  chain bursts only when `F > Σ τ_y`. A 1-thick wall bursts at `F>τ_y`, a 2-thick at `F>2τ_y` — identical blocks
  genuinely reinforce. (Replaces §8.3's undiminished `passes F_in`.)
- **The yield gate runs POST-RESOLVE, on the netted force — NOT on absolute `P`.** A solid buried in balanced
  water has huge `P` but ~0 net force ⇒ must NOT yield; only a one-sided imbalance (dam: water one side, air the
  other) gives net force. The imbalance exists only after the 6 faces are summed ⇒ a DECRYPT-side test, not the
  ENCRYPT Bingham term.
- **Scope (user, 2026-06-09): brittle block-push only** (scalar `F > Σ τ_y`: dam-burst, single-cell push,
  floating allowed) — NOT angle-of-repose / Mohr–Coulomb shear (a later module).

---

## §7 — Cohesion (`min_mass`) and the residue: BOUNDED + self-healing → fast-follow

**§8.7 flow gate (the cohesion rule):** a same-species flow of amount `f` (donor `D` → receiver `R`) is allowed
**iff** `R+f ≤ max` **AND** (`D−f == 0` *or* `D−f ≥ min`). Cross-species requires `f ≥ min`. Flows are **not**
quantized to `min`; the **only** ban is leaving or creating a cell at `0 < mass < min`. (Worked: water
`min125/max1000`; a 130 drop onto an 875 cell is **blocked** — 125 leaves donor at 5 `<min`, 130 overfills to
1005 — needs ≥ 250 to move 125 and keep ≥125 → `[1000, 125]`.)

**Residue — the corrected picture (✅ §12; the handoff's "~36 kg" was a mid-flow snapshot, not a settled state):**
- In a **settled** spread the engine leaves **NO persistent sub-min residue.** A transient sub-min frontier cell
  (**≤103 kg, 1 cell**) can appear *during active advance* and is **filled/healed within ~50 steps** as the
  frontier fills (smallest cell climbs `103 → 154 → 188 → 214` and stabilizes). 1D-walled spread: **0** sub-min
  at every step. **Repeated pours** (4000 steps, → 7019 kg, 32 cells): **0** persistent sub-min, **no
  accumulation.** Per-species mass conserved to float32 ULPs throughout.
- Per the ratified Fork-3 conditional (*"bounded → fast-follow"*), and since leveling ships on **conservation
  exact + spread correct + flecks bounded** — all met — **leveling ships now; cleanup is a tracked fast-follow.**
- **The cleanup task (banked):** harden DECRYPT so a flow never even *transiently* leaves `0<m<min` (drain-fully
  or coalesce). Its gate is a **strict test asserting NO sub-min cell in the SETTLED state** — which the engine
  **already passes**, so it is a GREEN guard against regression, *not* a RED-until-fixed blocker. The looser
  existing test (`subMin < 125`) must be replaced by this strict settled-state assertion. The *transient*
  per-step dip is the only open item, and it is low-priority (self-heals, no accumulation, no leak).

---

## §8 — Viscosity in the swap barrier = a BOUNDED PROXY (Fork-4: keep the code, fix the spec)

The live swap barrier is `R_pair = swap_kv·√(visc_i+visc_j) + swap_kc·min(min_mass)·g` (`engine_b.hpp:134–138`,
`swap_kv=150`, `swap_kc=3`), gated `forceDiff = (m_up − m_low)·g > R_pair` **AND** `dPE < −1e-3` (`:453/:467–471`).
The swap is a **pure permutation** (mass/matIx/T copied wholesale from the snapshot ⇒ bit-exact conservative).

- **Physics:** viscosity is a **rate**, not a **threshold** (§2). A viscosity threshold that can *block* a swap
  would falsely freeze a viscous/low-buoyancy pair that should slowly sink. **The canonical swap threshold is
  `buoyancy vs (cohesion + yield_stress)`; viscosity is the swap RATE.**
- **Why the engine works anyway:** it folds viscosity into the threshold as a **bounded proxy** for "viscous
  swaps are slow," valid *only* because every liquid pair in today's LUT (lava/water) has `buoyancy ≫` the
  viscosity term. Because the swap is a mass-conserving permutation, the proxy **cannot fabricate or leak mass**
  (verified: bit-exact).
- **Disposition:** **keep the code** (no surgery — correct for shipped materials; we need to converge). The spec
  states the mismatch is **knowingly accepted**. `swap_kv` stays in the manifest **labelled "bounded proxy,"**
  NOT forbidden. **Bank (required before any high-viscosity, low-buoyancy liquid):** move viscosity to a swap
  **rate/cadence** so a buoyant pair *always eventually* swaps. No real-LUT test exists for the latent case
  (synthetic Materials are banned) — documented as a **material-gated limitation.**

---

## §9 — ANTI-DRIFT APPARATUS (premise FIXED: every invariant ENSHRINED-AS-PROVEN runs first; INV-ELIG/INV-7 are build-gated TODOs, marked unrun)

The root cause of the v1/v2 oscillation was an invariant (INV-1b) **enshrined from a premise that was never run**
— it was *backwards* (it demanded leveling be driven by `p_dyn`, which is `0` at rest, so it would have REJECTED
the correct engine). v3's first anti-drift rule is therefore: **NO invariant, fork, or "keystone" is *enshrined as
proven* (given a ✅ in §12) until it has been RUN against the live engine and its number recorded there.** An
invariant that needs a not-yet-built feature is marked `(author with build)` and is **never** given a ✅ — INV-ELIG
and INV-7 are exactly such build-gated TODOs (currently unrun, by design, not oversight).

### §9.1 — Frozen symbol surface (checked-in manifest; adding a symbol = build failure)
**Persisted `Chunk` fields (`sim_engine.hpp`):** `{ matIx, T_curr, T_next, mass_kg, vx, vy, vz, p, void_ix }` —
**exactly ONE persisted pressure buffer (`p`)**; temperature is double-buffered (`T_curr`/`T_next`).
**`Globals` (14, `engine_b.hpp:26–91`):** `{ K, gamma, alpha, T_ref, g, dx, V, A, eps_mass, head_relax,
vel_damp, swap_kv, swap_kc, p_ac_scale }`. **File-scope physics knobs (NOT in Globals):** `CHI_COMPR_EPS=1e-3`
(`:108`), `LEVEL_MOB=0.01` (`:901`, the §8.4 leveling mobility — a real, frozen knob).
**Spec↔code map:** `A ≡ Chunk::p` (the persisted overburden) · `B ≡ own_weight_head()` (function, **not**
persisted) · `s ≡ matIx` · `m ≡ mass_kg` · `u ≡ (vx,vy,vz)` · `T ≡ T_curr/T_next`.
**FORBIDDEN (CI greps the diff):** `p_surf`, `c_head*`, any **new** `*_head` *field* or new Globals constant; any
**function/expression** of the form `(m|mass)/V·g·(dx|h)` / `ρ*g*…` that sources a horizontal pressure read
**other than** the one `own_weight_head` already blessed in §3; any `chi`/`χ` swap *gate*; any `swap_threshold`
global; any `Σ mass above`/column sweep; a **2nd persisted pressure buffer**; **folding `own_weight_head` into the
vertical kick / `Chunk::p`**. **NOT forbidden (corrected from v2):** `swap_kv` (it is the labelled bounded proxy
§8, not the banned class). **Dropped from v2's list as false-positives:** `k_scale`, `λ_scale` (do not exist in
code). INV-3 = `git diff` of struct/Globals members vs this manifest must be **empty**; adding a symbol edits the
manifest **in the same commit**. Transient `CellEncrypt/CellAccum/SwapRef/resolve-local` scratch is unfrozen if
recomputed each tick and never persisted.

### §9.2 — Property / invariant tests (ungameable; REAL LUT only; assert MOVED, not "bounded")
| id | invariant | pins | RUN? |
|---|---|---|---|
| **INV-A1** | sealed column: `p(depth d)` builds and LOCKS, base `≈ ρg·H` (H=8→0.9994, H=16→1.0000), settles, no per-interval growth | §3 A overburden | ✅ §12 |
| **INV-A2 (A→0 control)** | `p_ac_scale=0` ⇒ column depth `p ≡ 0` at all depths/ticks (B untouched) | §3.2(4): A carries depth | ✅ §12 |
| **INV-B1** | walled `[1000|500] → [750|750]` (`|A−B|≤1`), no upward leak, 1500 kg exact | §3 B leveling | ✅ §12 |
| **INV-B2 (B→0 control)** | `own_weight_head→0` ⇒ `[1000|500]` frozen forever (2 cells) | §3.2(3); kills NOTE-B | ✅ §12 |
| **INV-CB-a** | NO decision ranks/thresholds absolute mid-column `p`; selections read `own_weight_head`/density; force uses face-avg `½(p_i+p_j)` | §5(a) checkerboard-safe | ✅ §12 |
| **INV-CB-b** | checkerboard amplitude bounded (`≈ρg·dx` ceiling, no growth); `max‖u‖→~9e-5` | §5(b) | ✅ §12 |
| **INV-SWAP** | a swap is a bit-exact mass permutation; per-species exact; reads density not `p` | §0/§8 | ✅ §12 |
| **INV-ELIG** | liquid next to ONLY {solid, far-air} does **not** consume the solid; consumes air via the §4 chain | §4 eligibility | (author with §4 build) |
| **INV-COH (settled)** | after a spread SETTLES: **no** cell at `0<m<min`; repeated pours do not accumulate | §7 cohesion | ✅ §12 (GREEN guard) |
| **INV-7 (one pass)** | RESOLVE reads ONE pre-pass snapshot; ZERO post-update reads; `Fx,Fy,Fz` from ONE face-traversal; forward/reverse iteration ⇒ bit-identical; random pair `Δm_i=−Δm_j` | one-resolver | (author with build) |
| **INV-8 (τ_y finite)** | 1-thick bursts `F>τ_y`, 2-thick `F>2τ_y`, never `F<τ_y`; DORMANCY golden: all `τ_y=∞` ⇒ bit-identical to pre-§6 | §6 stacking | (active only with finite τ_y) |

*Existing GREEN that must STAY green for the RIGHT reason (assert MOVEMENT): flat-pool-level, connected-leveling,
hydrostatic-rest, yield_gate, eos, swap_resistance, conduction, conservation_levels.*

### §9.3 — Pre-answered forks (loud where an implementer will be tempted)
- **FORK-1 — "SCENE-1 won't level":** it's **B** (`own_weight_head`); INV-B1/B2. DON'T make A ρ-aware (NOTE-B,
  disproven), DON'T add a 4th head field (`p_surf`).
- **FORK-2 — "lowest-`P` picked a wall":** apply the §4 eligibility predicate; rank `own_weight_head`/density,
  never absolute `p`.
- **FORK-3 — "the depth profile is lumpy":** it's the §5 checkerboard (named, bounded, harmless via face-avg).
  DON'T add damping; DON'T widen the EOS band; DON'T touch `max==default`.
- **FORK-4 — "it rings / converges slowly":** O(H)-tick convergence is the accepted artificial-compressibility
  cost (§11 perf). Tune `head_relax`/`vel_damp` in range; a switch to two-step predictor+projection is an
  architecture change ⇒ **ESCALATE**. DON'T sub-cycle, DON'T add a damping field, DON'T column-jump-start.
- **FORK-5 — "an existing test went red":** TRIAGE against these invariants (stale vs regression, 00-MASTER-RULES
  §🧨). Don't game it; don't blindly satisfy it.
- **FORK-6 — "make it hold/sink like a solid":** use `yield_stress`/cohesion (threshold), NOT `viscosity` (rate).
  Viscosity in the swap barrier is a **labelled bounded proxy** (§8), not the law.
- **FORK-7 — "the relax coefficient needs a free constant":** ESCALATE. `c²` derives from EOS stiffness `K`
  (`c²=∂p/∂ρ`), it is **not** a new Globals constant and **not** `p_ac_scale` renamed.

### §9.4 — Independent oracle = a MECHANISM checklist (observable-only is INSUFFICIENT)
After implementation, a separate agent re-derives the observable **from this spec only**, on a **NEW geometry**
the implementer's tests did not use, computes an **analytic absolute reference** (`ρg·d` hand-calc, `±tol`), AND
**runs the §9.1 manifest diff itself** as a mechanism check. State plainly: a pure-observable re-derivation
**agreed with `own_weight_head` once** and would have blessed a wrong field — it is insufficient alone.

---

## §10 — Decisions log (rejected alternatives + WHY)

- **DEC-A — One resolver, artificial compressibility.** `P` relaxes over ticks; `F=∇P` same pass. REJECTED the
  classical two-step predictor + pressure-Poisson projection (global iterative solve = non-local, breaks GPU-local
  single-pass). Cost accepted: O(H)-tick convergence (FORK-4/§11).
- **DEC-B — A+B split is LABELED DEBT, not design; NOT ρ-aware `p_dyn`, NOT `p_surf`.** A = `divU` overburden
  (verified, stacks; carries depth AND the bulk of leveling), B = `own_weight_head` (verified — but only patches
  A's same-level FILL blind spot, §3.1). Per the frozen law the split violates "one `P`"; it is kept ONLY because
  A cannot yet be made correct at rest/edges, and **B is deleted at the single-`P` exit**. REJECTED v2's
  *premature* "delete B now" (B is load-bearing today — INV-B2), NOT the law's eventual delete. REJECTED NOTE-B
  (ρ-aware A — impossible, `divU=0` at rest) and the 4th-field `p_surf` (double-counts). REVERSES v2
  §3/§9.0/INV-1b. (§3)
- **DEC-C — eligibility predicate, ranks density/head not absolute `p`.** REJECTED naive lowest-`P` (ties a wall
  to air at `P≈0`; and absolute mid-column `p` is checkerboard-lumpy). (§4)
- **DEC-D — checkerboard accepted (named), base-contractual.** REJECTED "require smooth per-depth `ρg·d`" for v3
  (no consumer reads mid-column `p`; force uses face-avg). Smooth field (Rhie–Chow/MAC) banked. (§5)
- **DEC-E — residue bounded → fast-follow; settled-state strict test.** REJECTED "block on cleanup" (residue is
  transient/self-healing, no accumulation — §12) and "document & move on" (it IS a §8.7 violation transiently;
  cleanup is tracked, the test tightened to settled-state). Corrects the handoff's "~36 kg". (§7)
- **DEC-F — viscosity-in-swap = labelled bounded proxy; viscosity is RATE.** REJECTED "bless viscosity-as-
  resistance" (physically wrong — RT always overturns) AND "rip it out now" (correct for shipped LUT; need to
  converge). Bank viscosity→rate. (§8)
- **DEC-G — stacked yield `F_in − yield`, post-RESOLVE on netted force; brittle block-push only.** (§6)
- **Carried:** `max==default` (no EOS band); swap barrier = pair resistance not `swap_threshold`; no `χ` swap
  gate; antisymmetric ⇒ conservation; ENCRYPT→RESOLVE→DECRYPT one pass; three un-mixed maps (decomp §1).

---

## §11 — What ships now vs deferred + acceptance gate

| | now (v3) | deferred / banked |
|---|---|---|
| §3 A+B split | **YES** — fixes SCENE-1 (B levels, A depth); no new field | — |
| §4 §8.5 lateral mover + eligibility | **YES** — pool→air spread / dam-break | transitive multi-hop (stays over-ticks) |
| §5 checkerboard (named, harmless) | **YES** as documented contract + guardrails | smooth field (Rhie–Chow/MAC) |
| §7 cohesion / residue | **YES leveling ships**; transient self-heals, bounded | settled-state strict test + transient drain (fast-follow) |
| §8 viscosity-in-swap proxy | **YES** (keep code, label proxy) | viscosity→swap-rate (before high-visc low-buoyancy liquid) |
| §6 granular stacked yield | **SPEC ONLY** (`τ_y=∞` dormant, guarded by INV-8 dormancy golden) | the trigger (DEC-4); shear/repose |
| wall-reaction absolute-`p` read | **YES** (empirically benign, bounded) | migrate to gradient form (if needed) |

**Perf gate:** O(H)-tick convergence is the accepted artificial-compressibility cost; the **~100× resolver cost
vs the old local passes is a GATE, not an in-game surprise** — add a ms/cell ceiling to the plan and watch
in-game lag. A real speed fix = two-step projection = ESCALATE.

**Acceptance gate:** §9.2 invariants GREEN on the **real LUT** (`core/src/main/resources/data/orge/orge/
materials/*.json`, never synthetic) — assert **MOVED**, not "bounded" — **+ the in-game re-audit** (leveling,
pool→air spread, lava sinks, no *settled* residue/ghosts). The headless invariants are necessary; the in-game
audit is the final oracle.

---

## §12 — VERIFICATION APPENDIX (the probe numbers — engine HEAD `7f3ce82`, real LUT, dt=0.5)

*Every claim in this spec traces to a run here. Probes archived under `/tmp/reaudit`, `/tmp/colcheck`,
`/tmp/residcheck`, `/tmp/wallcheck`; reproducible against `engine_b_real_lut.hpp`. Two workflows + four
main-agent probes; the crux was independently re-run after a V2a/V2b disagreement.*

- **A overburden (sealed column):** H=8 `p_bot 27722→49581→74454→79953` LOCKS by t≈1500 (`79953/80000`=0.9994),
  flat to t=5500, mass `8000.0000` exact. H=16 LOCKS `159999.73/160000`=1.0000 by t≈6000, flat to t=9500, mass
  exact. *(V2b's "unbounded" was a too-short window — t=400 reading of 43558 is mid-fill.)*
- **A→0 control:** `p_ac_scale=0` ⇒ `p_bot = 0.0` at every t (depth collapses), `own_weight_head` intact, mass
  exact. ⇒ A (`divU`/`Chunk::p`) is the SOLE vertical-depth carrier.
- **`p_dyn`=0 at rest:** single supported cell `p=0.00000` every tick (patched-clean build), `divU=0`
  (`engine_b.hpp:1019–1026` skips the wall face; comment `:1090–1091` "velocity-zeroing leaves divU==0").
  Coefficient is the flat `p_ac_scale=2000` (`:90`), no ρ — ⇒ NOTE-B impossible.
- **B leveling:** walled `[1000|500] → 750.0006 | 749.9994` (`|A−B|=0.0012`) by t≈300, no upward leak, 1500.0000
  exact. **B→0 control:** patched `own_weight_head→0` ⇒ frozen at `[1000|500]`, 2 cells, never spreads.
- **Checkerboard:** H=16 converged `p` `0,20000,20000,40000,…,159999.7`; per-cell `Δp` `20000,0,20000,0,…`.
  Amplitude saturates `≈20000` ceiling (`19866@t2000 … 20000@t12000`, no growth); `p_bot` bit-frozen; `max‖u‖
  1.8→0.66→8.8e-5`.
- **Guardrail-a (source-confirmed):** only absolute-`p` decision = open-face force `½(p_i+p_j)` (`:952/:989`);
  §8.4 `dP=own_weight_head(i)−own_weight_head(j)` (`:898–902`); §8.5 ranks `P_L=own_weight_head` (`:633`); swap
  uses density `forceDiff=(m_up−m_low)·g` vs `R_pair` (`:467–471`). Exception: wall reaction reads one-sided
  absolute `P` (`:1003–1009`) — a force, not a selection.
- **Wall-reaction (asymmetric):** tall column, stone wall left / open air right: `max|vx| 0→1.18→1.80` (legit
  dam-break) `→0.022→…→0.0055`; mass `8000`±1e-3; spread 9→12 cells then settles. **Benign.**
- **Residue:** 2D spread (3000 kg): t=50 one cell `103.3` (`<125`), **gone by t=100** (smallest `154→214`
  stabilizes); 1D-walled: **0** at every step; repeated pours → 7019 kg/32 cells: **0** persistent sub-min, **no
  accumulation**; per-species exact to float32 ULP. ⇒ transient/self-healing, BOUNDED.
- **Swap / §8.5 conservation:** `R_pair = swap_kv·√(visc_i+visc_j)+swap_kc·min(min_mass)·g` (`:134–138`,
  `swap_kv=150`, `swap_kc=3`); mass/matIx/T copied from snapshot ⇒ bit-exact permutation. §8.5 spread: grand drift
  `3.6e-4` on 44094.8 (`~8e-9` rel = a few ULPs), WATER/AIR each independently conserved (no cross-species leak),
  STONE bit-exact, boxed-in air with no sink correctly **rejected** (`Δm=0`).
- **Symbol surface (source):** 1 persisted pressure buffer `Chunk::p`; Globals 14 members (no `k_scale`/`λ_scale`);
  file-scope `CHI_COMPR_EPS=1e-3`, `LEVEL_MOB=0.01`.

---

*Next: `superpowers:writing-plans` → a staged, TDD, subagent-driven plan on `rebuild` that implements/hardens the
A+B split (§3) and §4 lateral mover, each gated by its **already-RUN** §9.2 invariants + the §9.4 mechanism
oracle, with the §7 cleanup and §8 viscosity-rate as tracked fast-follows. No off-spec mechanism (no EOS band, no
global Σ, no Poisson solve) without user sign-off.*
