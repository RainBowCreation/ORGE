# Engine B — Stage-2: Conservative Buoyant Displacement (emergent cross-species swap)

**Date:** 2026-06-06
**Track:** `rebuild` (Engine B). Worktree `/home/claude/ORGE-B`; engine `ORGE-ENGINE` git-worktree on `rebuild`.
**Baseline (safe state):** parent `0c72cbd`, engine `20c4320`.
**Governing specs (this design ADDS NOTHING; it maps them to code):**
- `docs/superpowers/specs/2026-06-04-engine-b-velocity-field-design.md` — §2.2 EOS/χ, §2.3 emergent buoyancy, §2.4 absorb vs reflect, §4.1–§4.4 the unified step, §4.4 implementation guard.
- `docs/superpowers/specs/2026-06-04-engine-b-unified-formula.md` — §B encrypt, §C.2/§C.5 resolve, §D.4/§D.5 decrypt, §E conservation, §J.5 hydrostatic.

**The one rule for this work:** *everything must follow the spec.* No mechanism appears here that is not in the two specs above. Where the specs use words ("absorb", "reflect", "swap if force is large enough", "no density-threshold"), this document fixes the single faithful code realization and nothing more.

---

## 1. Problem (what the safe state defers)

Stage-1 shipped a **conservative SAFE STATE**: a `crossOccluded` guard (`engine_b.hpp:266`, `:360`) makes a face between two **occupied different-species** cells pass **no advective ṁ and no pressure kick**. Consequence: fluid placed in the live `orge:air` medium sits visible + conserved but **cannot move through air** — the safe state explicitly defers displacement to Stage-2 (`engine_b.hpp:256–263`, `:493–499`).

The safe state exists because of a **structural fact of the cell model**: Engine B is **one species per cell** + first-order upwind (spec §4.3 "L6 sharp", formula §D.5 "one species per cell"). At an occupied↔occupied different-species face, any **partial** cross-species transfer leaves the receiver holding a **mixture**, which the model cannot represent — so the commit must either relabel (air→water = the audit-#1/#3 "1 bucket → 1740 kg" fabrication) or keep-label (water→air = vanish). Both break per-species conservation. `crossOccluded` froze that face rather than ship either.

## 2. The spec's mechanism (verbatim mapping)

The spec does **not** leave displacement undefined — it prescribes an **emergent cell swap**, gated by absorb/reflect, with an explicit no-density-threshold guard:

| Spec location | Text | Role in this design |
|---|---|---|
| design §4.3 (l.205) | "advective in/out ⇒ Δmass (+species: **merge** if same species, **relabel/swap if different**, L6 sharp)" | the cross-species commit **is** a swap |
| design §4.4 (l.216–220) | "water sinks through *air* (air absorbs/compresses), tube `[W,A,A] → [A,A,W]`" … "the **'swap if force is large enough'** exception — automatic" | displacement is the swap firing when the drive wins |
| design §2.4 (l.146–147) | "**Compressible** cell (χ≈1): **absorbs** … the flow passes through" / "**Incompressible** cell (χ≈0): **reflects** … its `p` shoots up the stiff `^γ` ramp" | the **gate**: absorb ⇒ swap, reflect ⇒ pin |
| design l.93, l.98 | "`ρ·g·z·v` … heavier ⇒ more downward PE flux" / "net **energy-lowering exchange** ⇒ **cell swap** (two cells trade contents when it lowers total energy)" | **direction** is emergent from the energy flux `W`, not a density compare |
| design §4.4 (l.224) | **Implementation guard:** "there must be **no** `if(denser_above) swap()` pass and **no** density-threshold test. Order is whatever the energy flux settles to." | the hard constraint this design obeys |
| formula §C.5 (l.134–141) | absorb/reflect emergent from EOS, capacity backstop | same gate, math form |
| formula §D.5 (l.185–190) | "full-cell **swap** is the Δm total change" | swap = the conserving Δm |

**Conclusion (forced by the specs, not chosen):** the Stage-2 mover is a **full-cell swap** of two cells' entire contents, fired by an **emergent absorb/reflect** gate, with **direction from the energy-flux drive `W`** (which already carries `ρ·g`), and **no `molar_mass` comparison branch**. `crossOccluded → no-flux` becomes `crossOccluded → energy-gated swap`.

## 3. Why this is the *only* per-species-conserving realization on this cell model

A swap is a **permutation** of the two cells' state `(m, s, T, v)`. Therefore **grand mass, energy, and momentum are exact by construction** — invariant under the operation, independent of magnitudes. This is *structurally incapable* of "1 bucket → 1740 kg" (the failure that reverted three times): no quantity is created or destroyed, only relocated. Any **partial** cross-species transfer, by contrast, needs a mixture cell the model lacks (formula §D.5 defers the sub-cell representation). Hence: occupied↔occupied cross-species transport **must** be a full-cell swap. This is not a design preference; it is what the one-species model permits.

## 4. The gate (absorb vs reflect, emergent — §2.4)

At a cross-species occupied↔occupied face, in Resolve:

1. **Drive.** Use the existing face energy-flux drive `W` (`engine_b.hpp:278`) — it already mixes the two cells' `w` recovered from `E/(ρh)`, and `E` carries the gravitational + kinetic + pressure channels (§B), so the **denser cell contributes more downward `W`** (design l.93). `W` sets the candidate **direction** (which cell is the donor / would descend). *No `molar_mass` comparison is read to decide direction.*
2. **Absorb vs reflect.** The receiver's response is its **own EOS pressure** `p_j` on the stiff `^γ` ramp (§2.2/§C.5):
   - **Absorb** (receiver compressible, χ≈1, or below its wall ⇒ `p_j` low relative to the drive): the swap **fires** — contents trade across the face. This is "the flow passes through" realized as a single-cell relocation per tick (the discrete one-species form of "it compresses").
   - **Reflect** (receiver incompressible + full, χ≈0 ⇒ `p_j` spikes up the `^γ` wall and exceeds the drive): **no swap**; the donor's momentum is **redirected** to open faces (the existing pressure-kick / free-slip reaction already does this for non-occluded faces). This is "balls in a tube" pinning (design §4.4, l.218–220).
3. **"Swap if force is large enough" — rate limiting via persisted `v`.** A cell at rest does not swap on tick 1; the gravitational/pressure drive accumulates into the **persisted velocity** channel (the Stage-1 reason velocity is persisted) until the accumulated drive crosses the receiver's reflecting threshold, then one swap fires — **one cell per tick = the CFL cap** (§D.4). This makes a **stable** interface (light-over-heavy, e.g. air-over-water) **never fire** (the energy-lowering test is already satisfied; swapping would *raise* PE), and prevents teleport/oscillation at a flat surface.

The gate compares the **drive `W`/momentum** against the **receiver EOS pressure `p_j`**. Both are emergent (gravity + EOS). `molar_mass`/`ρ` enter only through the **magnitude** of the flux (legitimate per design l.93), never as an `if(denser_above)` branch (guard l.224 satisfied).

## 5. The commit (Decrypt / §D.5 generalized)

On a fired swap across face (i,j): exchange `(m, s, T)` of the two cells and resolve the **velocity** so the descending parcel keeps its downward `v` and the rising parcel receives the upward reaction (momentum-conserving; this is where buoyant-inversion + slosh-damping live, design "inertia+reflection" stage). Antisymmetric by construction ⇒ conservation backstop (§E) finds ~0 residual. §D.5's empty→refill relabel (current `decrypt_world`) is the χ→1-into-vacuum limit of the same operation and is **kept**; Stage-2 adds the occupied↔occupied case. Capacity clamp / per-receiver + per-donor budgets (`engine_b.hpp:292–340`) remain — a swap never exceeds `max_mass` because it relocates existing within-bounds contents.

## 6. Conservation is the gate — what stays green, what turns green

**Sacred (must never regress):** `engine_b_safe_state_test` — 1 bucket in an air world, 120 steps — **GRAND movable mass exactly constant** every step, water bounded. A swap is a permutation ⇒ this holds by construction.

**Red tests written FIRST** (reproduce the in-game scenario — **air medium, never vacuum**; gate on bounded + grand-conserved + per-species, *not* on "it spreads"):

1. **Fall:** 1 bucket on a floor in air → grand-conserved **and** falls to the floor / forms a 1-cell pool; displaced air ends up above. Assert per-species water ≈ 1000 ± ε **and** per-species air conserved ± ε; water bounded (< ~40 cells).
2. **Level:** two-height pool / column → equalises (same-species channel) with both species conserved; flat pool never climbs (§J.5).
3. **Inversion / pinning:** water above air → buoyant inversion completes, conserved; **air above water → no spurious motion** (hydrostatic, §J.5); lava on water in a 1-cell tube → reflects/pins (`[L,A,W] → [A,L,W]` and stays, design §4.4).
4. **`accept TEST8`** (gas→vacuum) restored from `DEFER` to a real `CHECK`.
5. **Invariants:** no cell > `max_mass`; no NaN; no 0K/6000K temperature ghosts.

## 7. Scope guard (what this is NOT)

- **No new pass, no DFS chain, no swap *pass*** — the swap is a **commit inside the single Resolve/Decrypt**, replacing the `crossOccluded` branch (design "TWO passes, ONE calculation").
- **No material special-case by id** — `orge:air` is treated by its EOS (χ from min/default/max_mass) like any cell; the engine never reads its id.
- **No Java-layer reconciler hack** — the reconciler stays a backstop (§E, design L7).
- **No `if(denser_above)` / density-threshold** — guard l.224.
- **No own-pressure buoyancy predictor in `w`** — explicitly removed by the spec (formula l.69, l.266, l.359); buoyancy stays emergent from the pressure flux + gravity in the energy channel.
- **No air retune** — air's EOS numbers are a §G.2 (Stage-4) calibration question; Stage-2 changes mechanism only.

## 8. Definition of done

Engine cheap tier green (incl. the new red tests now green + safe_state still grand-conserved); `.so` rebuilt; `:core:test` + `:core:integrationTest` skipped=0 0-fail on the real `.so`; both loaders build; ResidentLut golden regenerated if the trajectory changed; engine + parent gitlink pushed to `origin/rebuild`; memory updated; then **in-game re-audit** (the real gate — headless has repeatedly missed live behavior).
