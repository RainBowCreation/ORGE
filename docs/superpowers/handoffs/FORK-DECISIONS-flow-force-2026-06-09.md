> # ⚠ NOTE 2026-06-10: the FRAME (force > resistance) stands. Fork-1's "bless A+B" and Fork-4's
> "keep the viscosity-threshold proxy" are superseded by the v4 spec
> ([`../specs/2026-06-10-engine-b-unified-spec-v4.md`](../specs/2026-06-10-engine-b-unified-spec-v4.md)),
> which executes exactly the exits those forks banked (single-`P` solve; viscosity → swap cadence).
> Fork-2 (checkerboard): v4's red–black relaxation damps the mode. Fork-3 (residue): v4 §6.4.

# Engine-B flow/force — Fork 1–4 decisions + the movement-law frame (user-ratified 2026-06-09)

**Purpose:** the ratified answers to the spec-rewrite/audit agent's four open forks, plus the unifying frame
they hang off. Feeds the v3 spec rewrite (see `/tmp/handoff-spec-rewrite-2026-06-09.md` and
`[[engine-b-unified-flow-force-spec]]`). **Read the frame first; the four decisions reference it.**
Companion to the proven verdict (probe `/tmp/reaudit/probe.cpp`): `p_dyn=0` at rest ⇒ NOTE-B's unify is
impossible; the SPLIT is correct.

---

## Frame: the movement law (pressure builds the field; force moves mass)

**One principle — `force > resistance`. Pressure is a scalar that moves *nothing*; only force (a gradient /
imbalance / density-difference) moves mass. `yield_stress` is crossed by *force*, never by a standalone
pressure value.**

| mechanism | driving FORCE | reads | depth-dependent? | RESISTANCE |
|---|---|---|---|---|
| **FLOW** | `−∇P` | `P` (= p_eos + A + B) | yes | ≈ 0 (fluid) |
| **PUSH / yield** | net `−∇P + g + external` | `P` | yes | `yield_stress` |
| **SWAP** (vertical immiscible reorder) | **buoyancy** `(ρ_up−ρ_low)·g·V` | **density (mass), NOT `P`** | **NO** (overburden cancels — Archimedes) | **cohesion + yield_stress** (NOT viscosity) |

- The SWAP reads **density, not `P`** → immune to every `P` subtlety (the A/B split, the stairs).
  Pressure-at-depth can't trigger a swap; only a density inversion can.
- A pressure **imbalance** (`−∇P≠0`) is a *force* → it can PUSH/yield a cell. That's PUSH, not SWAP.
- `yield_stress` gates both SWAP and PUSH (a solid `yield=∞` never swaps, never yields). Viscosity gates
  neither — it only sets the *rate* once moving.

---

## Fork 1 — pressure-by-depth: bless the TWO ingredients (A + B). Don't delete either.

`P = ρg·d = ρg·(k·dx)` [**A**: overburden, the k cells above] `+ ρg·(dx/2)` [**B**: this cell's own half].
Exact decomposition of a hydrostatic column — not redundant patches.
- Delete **B** → flat-ground leveling dies (proven: patch B→0 freezes `[full|half]`). NOTE-B's mistake.
- Delete **A** → pressure-at-depth dies (B is `ρg·dx/2`, depth-independent). The "B alone" option = mirror mistake.
- **A is NOT a column-sum** (banned). It's local `divU` convergence: 6-neighbour only, propagates 1 cell/tick,
  *relaxes to* "weight above" over ~H ticks, and correctly **stops at a solid** (a shelf shields fluid below).
  "Weight above" is the steady value, not the operation.
- **Composition rule (or you re-introduce the air-launch bug):** A drives the *vertical/depth* force; **B
  drives *horizontal/leveling* only; B never enters the vertical kick.** Safe because same-level cells share A
  (cancels in the horizontal gradient) and stacked cells never use B vertically.
- **Verify with 4 invariants, each RUN against the engine before enshrining** (INV-1b was backwards because it
  was written from a premise and never run): (1) deep column reads `ρg·d`; (2) `[full|half]→[¾|¾]` levels;
  (3) B→0 freezes flat ground; (4) A→0 collapses depth.

## Fork 2 — the stair-step: ship it; mid-column allowed lumpy.

It's the standard **odd-even decoupling ("checkerboard")** of a collocated pressure scheme — a *named*
artifact, not a bug; write the name in the spec so it isn't re-litigated. Harmless because the **force uses
face-averaged `½(p_i+p_j)`**, and those face values climb smoothly even when cell values stair-step → column
balances, bottom exact, no spurious flow. The lumpiness sits in the gradient operator's null space (no force).
- **Two guardrails:** (1) confirm no decision ranks *absolute* mid-column `P` — the §8.5 lowest-`P` consume
  ranks neighbour `P`; verify it only compares free-surface/air (`P≈0`), else add a tolerance (the SWAP is
  unaffected — it reads density). (2) Soak-test that the checkerboard stays bounded (it's undamped).
- **Banked fix** if a future feature needs a smooth field: Rhie–Chow interpolation or a staggered (MAC) grid.

## Fork 3 — residue specks: ship leveling; make cleanup an explicit tracked task. NOT "document & move on."

- **Correct the number:** it's **~36 kg** (28 + 8), not 0.003 kg — the handoff figure was stale; fix it in
  handoff/memory/test-comment.
- **It's a §8.7 *violation*, not aesthetics:** §8.7 bans any cell at `0 < m < min`; these specks are exactly
  that. The current test (`subMin < 125`) is **too loose** — it greens 36 kg and hides it.
- **Fix = the banked T5 `stage3_cleanup`** (DECRYPT sub-min drain-fully-into-neighbour / coalesce). Explicit
  plan task, gated by a **strict** test (*no cell left at `0<m<min`*) that stays RED until cleanup lands.
- **Severity check:** soak repeated spreads — if specks **accumulate**, it's world-pollution (bump priority);
  if **bounded**, fast-follow. Leveling ships on: conservation exact + spread correct + flecks bounded.

## Fork 4 — viscosity in the swap: keep the code, but DON'T bless "viscosity-as-resistance." Fix the spec.

- **Physics:** viscosity is a **rate**, not a **threshold**. Heavy-on-light is Rayleigh–Taylor unstable — it
  *always* overturns; viscosity only sets the speed (Earth's mantle is 10²¹ Pa·s and still convects). A
  viscosity threshold that can *block* a swap would falsely freeze a viscous/low-buoyancy pair that should
  slowly sink. The old ban was **right in principle**, just incomplete (never said viscosity *is* the rate).
- **Why the engine works anyway:** it folds viscosity into the threshold as a **bounded proxy** for "viscous
  swaps are slow," valid *only* because every liquid pair in today's LUT (lava/water) has buoyancy ≫ the
  viscosity term.
- **Do now:** keep the code (no surgery — correct for shipped materials; we need to converge). Spec wording:
  swap **threshold = buoyancy vs cohesion + yield_stress**; **viscosity = swap rate**; document current code as
  a proxy.
- **Bank (required before any high-viscosity, low-buoyancy liquid):** move viscosity to a swap **rate/cadence**
  so a buoyant pair *always eventually* swaps. Keep `swap_kv` in the manifest **labeled "bounded proxy,"** not
  forbidden. No real-LUT test exists for the latent case (synthetic Materials are banned) — document it as a
  material-gated limitation.

---

*Mutually consistent: pressure builds the field (1–2), force moves mass against a resistance (4 + frame), the
SWAP is the density-driven branch that sidesteps `P` (frame), and the two known gaps (residue, viscosity-rate)
are tracked, not hidden. Next: fold into the v3 spec rewrite.*
