# Drift audit — Module D: Movement gates (§5) + mass-flux 5-pass RESOLVE (§6) + law #5/#9

**Date:** 2026-06-15 · **Scope:** §5.1/5.2/5.3, §6.1/6.2/6.3/6.4, law #5, law #9.
**Method:** read-only; SOURCE OF TRUTH = spec; comments discarded; only executable code cited.
**File:** `ORGE-ENGINE/core/engine_b.hpp` (3171 lines).

---

## Severity counts

- **BLOCKER:** 0
- **MAJOR:** 2 (both spec-acknowledged DEBT, not silent code bugs)
- **MINOR:** 2 (1 acknowledged debt, 1 cosmetic field-name reuse)
- **Compliant items verified:** 8 of the 8 numbered verify points (1–8), with the two MAJORs being the cross-species lateral / displacement scope, which the spec itself flags as T4-owned.

No item in this module is a CODE-BUG diverging from a settled spec requirement. The two MAJORs are
implementations that are narrower than the spec's general statement, and the spec text already carries the
`owner T4` / future-path tags for exactly those gaps.

---

## MAJOR findings

### D-MAJOR-1 — §6.3 cross-species lateral displacement is GAS-cell-only, not general "liquid under pressure enters a gas/vacuum neighbor"
- **Spec §6.3 (+ §6.1 R0 priority "else the lateral §6.3 face"):** cross-species movement = swap or
  **displacement-swap**, a pure permutation where "liquid under pressure enters a gas/vacuum neighbor by
  swapping volumes," handled as an R0 face-swap candidate with driver `P_donor − Φ_anchor`, priority after
  vertical swaps. Stated generally over the lateral face.
- **Code actually does:** the displacement pre-pass (`engine_b.hpp:1619–1753`) iterates **per occupied GAS
  cell** (`if (!is_compressible(mats.byIx(gsp))) continue;` at 1638) and **evicts the gas UPWARD into `k`
  = the cell directly above** (`int k = idx(x,y+1,z);` at 1642), then pulls one liquid min-quantum laterally
  in. The driver `P_L − phi_g` (`drive = CL->P[li] - phi_g;` at 1700, `phi_g = gas_anchor_v42(...)` at 1681)
  matches `P_donor − Φ_anchor`. It is NOT modeled as an R0 SwapCand permutation; it is a bespoke
  two-step (gas→up, liquid→in) bookkeeping transfer with a `forceSpecies` relabel (1750). It is also
  **not a pure volume permutation**: the liquid sends only a `min_mass` quantum (`fq` at 1708), not its full
  volume, and the gas leaves entirely upward — so the "swap volumes" permutation framing of §6.3 is
  approximated, not realized.
- **Severity:** MAJOR. **Class:** spec-acknowledged DEBT. The code comment ties it to "T4/§6.3," and the
  spec's own R0 text (§6.1) and `[LAW-AMEND-v42-A2 / owner T4]` flag the lateral/hetero-gas path as T4-owned.
  Functionally conserving (antisymmetric `dm`/`dpx`/`dE`, `g_submin_refill_gated`-guarded), so no law-#9
  violation; the drift is *coverage* (a liquid pushing laterally into a vacuum cell with no gas above to
  evict, or into a SEALED gas, does not displacement-swap — it is the deferred horizontal push-chain,
  comment at 1614–1615).

### D-MAJOR-2 — buoyant swap is VERTICAL-only; the §6.1 R0 "else up, else lateral" general priority chain is not implemented for non-displacement swaps
- **Spec §6.1 R0:** deterministic priority "(1) vertical down, else up (buoyant/§7 swaps and §6.3
  displacement-swaps), **else the lateral §6.3 face**." So a swap partner may be selected on a lateral face.
- **Code actually does:** the R0 swap-candidate loop hard-gates `if (ny == y) continue;`
  (`engine_b.hpp:1358`) — the buoyant/§7 swap fires on **vertical faces alone**. Lateral cross-species
  movement is delegated entirely to the gas-displacement pre-pass (D-MAJOR-1), which is itself gas-only and
  upward-evicting. So there is no general lateral swap path for, e.g., two occupied liquids of different
  species side-by-side.
- **Severity:** MAJOR. **Class:** spec-acknowledged DEBT / behavior-preserving design choice. The code
  comment (1347–1357) states the vertical-only guard deliberately restores the suppression the deleted
  `dPE<−1e-3` co-gate provided and routes lateral to §6.3. The spec §5.3 confirms the `dPE` co-gate is
  deleted; §6.1 routes lateral to §6.3; §6.3 is the gas-displacement path. So this is internally consistent
  with how the spec partitions the work, but the *general* lateral §6.3 face (non-gas cross-species
  permutation) is not present — same coverage gap as D-MAJOR-1, viewed from R0.

---

## MINOR findings

### D-MINOR-1 — §6.1 R0 priority ordering: candidates sorted by raw `drive` (Δρ_eff·g·V), not `Δρ_eff·g·V − R_pair`
- **Spec §6.1 R0 (2):** "strongest `Δρ_eff·g·V − R_pair`."
- **Code:** the SwapCand is pushed with `prio = drive` (`cands.push_back(SwapCand{... , drive});`
  `engine_b.hpp:1424`) where `drive = (rhoUp − rhoLow)·g·V` (1393); the conflict-free greedy sort is
  `a.prio > b.prio` (1446–1447) — it sorts by raw drive, NOT `drive − resistance`.
- **Severity:** MINOR. **Class:** CODE-BUG (deviates from the literal §6.1 ordering), but low-impact: the
  threshold gate `if (drive <= resistance) continue;` (1396) already removes all non-passing pairs, and the
  code comment at 1424 argues `R_pair` is "constant per pair" so `drive` is the discriminator. For
  same-species (R_pair = τ_y = 0) the two orderings are identical; for cross-species the subtraction can
  reorder near-threshold competing candidates differently than the spec. Determinism is preserved (the
  final tie-break is coordinate-parity-free here — it relies on `cands` insertion order under a stable-ish
  sort, see D-MINOR-2). Worth a one-line fix to subtract `swap_resistance` into `prio`.

### D-MINOR-2 — §6.1 R0 tie-break "(3) lower coordinate-parity" is not implemented
- **Spec §6.1 R0 (3):** lowest tie-break = "lower coordinate-parity."
- **Code:** `std::sort` (not `stable_sort`) on `prio` alone (`engine_b.hpp:1446`); ties in `prio` resolve by
  whatever order `std::sort` leaves, then greedy first-come claiming (1448–1463). There is no explicit
  coordinate-parity tie-break.
- **Severity:** MINOR. **Class:** CODE-BUG (missing spec tie-break) but practically harmless: exact-`prio`
  ties between two distinct candidate pairs are measure-zero in float, and `INV-7` (forward/reverse iteration
  bit-identical) is the real determinism guard — the iteration order is itself deterministic via
  `ORGE_FOR_*`. Note `std::sort` is not stable, so reverse-iteration could theoretically reorder true ties;
  if `INV-7` passes this is moot, but the spec's stated tie-break is absent.

---

## Compliant items (verified against executable code)

1. **`swap_resistance` formula (verify #1) — COMPLIANT.** `engine_b.hpp:205–210`:
   `yld = max(yieldStress_i, yieldStress_j)`; `if (sameSpecies) return yld;` (line 207 — same-species = τ_y
   ONLY, no cohesion); cross-species `coh = swap_kc·min(minMass_i,minMass_j)·g` (208), returns `coh + yld`.
   **No `swap_kv·√μ` term anywhere.** Matches §5.3 / §0 table / §7.3 exactly. Viscosity is NOT in the gate.

2. **Swap cadence + SECONDS floor (verify #2) — COMPLIANT.** `engine_b.hpp:1410–1417`:
   `t_swap = max(G.t_swap_min, (μ_i+μ_j)/(dRhoEff·g·dx))` with `dRhoEff = |ρ_eff(up)−ρ_eff(low)|`,
   `denom = dRhoEff·g·dx`, fallback `t_swap_min` when `denom ≤ 0`. `G.t_swap_min = 0.5f` (line 77) is a
   literal **seconds** floor — NOT `max(dt,…)`. Accumulate `charged = swapReady + dt/t_swap` (1417);
   under-ready (`charged < 1`) → `continue` (1423); persistence carries remainder on fire / keeps charged /
   resets on payload change (`persistSwapReady`, 2788–2799). Matches §5.3 precisely.

3. **Five passes R0→R1→R1.5→R2 + buffers + σ/ρ′/commit (verify #3) — COMPLIANT.**
   - R0 swap-intent: `engine_b.hpp:1249–1463` (writes `swapChoice`/`out.swap[]`).
   - R1 mutuality + flux-intent + donor σ: mutuality 1478–1491 (`swapMutual_i = choice_i=j ∧ choice_j=i`,
     line 1489); cohesion/canDrain + explicit σ 1493–1601; `σ_i = min(1, budget_i/outFlux)` with
     `budget_i = fullDrain ? m : max(0, m − minMass)` (1592–1598) — budget = m − minMass per §6.1.
   - R1.5 receiver scale: 1812–1890; `ρ'_i = min(1, room_i / Σ inbound f·σ)` (1885–1886); inbound sums
     `legalized_face_intent · σ` over donor faces.
   - R2 commit: 1899–2205; force impulse applied (1941–1946), then `mdot` room-clamped (2067–2070),
     `· σ_donor` (2102–2114), `· ρ'_receiver` (2129–2130), recvRoom backstop (2131–2141). Commit flux =
     `f·σ·ρ'`. Antisymmetric (both endpoints read identical buffers). Matches §6.1.

4. **Whole-cell flux-XOR (verify #4) — COMPLIANT.** A `swapMutual` cell zeroes ALL six flux intents:
   R2 `if (smi && (*smi)[i]) continue;` (`engine_b.hpp:1925`) skips the cell's entire face loop (and the
   §4 force impulse) — whole-cell, not per-face. The neighbour side is also gated:
   `if (smj && (*smj)[j]) continue;` (1973). R1.5 treats a swapMutual cell as inert ρ′ (1829) and skips
   swapMutual donors (1867–1868). This is the §6.1 "zeroes ALL six flux intents" whole-cell XOR.

5. **Cohesion legalization §5.2 arithmetic (verify #5) — COMPLIANT (via the A-7 σ realization).**
   The literal `f ← D − min / f ← D / f ← 0` branches are intentionally ABSENT; §5.2 [LAW-AMEND-v42-A7]
   makes the `0<D−f<min` full-merge branch UNREACHABLE under INV-NOSUBMIN, and the room/capacity clamp
   `f ← min(f, maxMass−R, D)` is realized as `legalized_face_intent` (`cap = min(room, headroom_donor)`,
   `engine_b.hpp:1796–1799` and the R2 mirror 2067–2070) composed with the donor σ (budget = m−minMass).
   Worked check of §5.2's 130→875 example: legalize `f ← min(130, maxMass−R=1000−875=125, D=875) = 125`
   demand, then σ caps the donor's total outflow to budget = m−minMass. For a single full-drain or
   budget-limited donor the residual stays ≥ minMass, giving the spec's `[125|880]` / `[125|1000]` end
   states by construction (donor never lands in (0,min); receiver `≤ max`). The empty-refill twin is gated
   at 2170–2184 (`g_submin_refill_gated`). This is the spec-sanctioned realization, not the literal branch
   — and §5.2/A-7 explicitly says the literal branch is unreachable, so the absence is COMPLIANT.

6. **Cross-species never partial-fluxes (verify #6) — COMPLIANT.** R2 computes
   `crossOccluded = (mi != mj) && !si_vac && !sj_vac` (`engine_b.hpp:1996`) and the advective channel runs
   only `if (!wall && !crossOccluded && …)` (2023). The additive same-species/into-vacuum gate is also in
   the canDrain pre-pass (`if (mj != mi && !j_vac) continue;` 1556) and R1.5 (`if (!vacuum && mj != mi)
   continue;` 1871). Cross-species occupied faces are no-flux; species crosses only via §7 swap or the §6.3
   displacement path. DECODE additionally zeroes into-interface velocity (2984–2989). Matches §6.3.

7. **Over-max / sub-min invariants (verify #7) — COMPLIANT.**
   - **INV-NOOVERMAX:** R2 room clamp `cap = min(room, headroom_donor)` with `room = max(0, maxMass − m)`
     (2067–2070) on every flux path — no freeze exemption. The freeze-into-lower-max relabel is handled by
     `freeze_evict_world` (2599–2696) which evicts surplus same-pass or DEFERS (no transient over-max), and
     DECODE skips the lower-max trigger (`maxMass(tgt) >= mNew` guard, line 3093). The §6.3 gas eviction
     uses an EXACT room gate `if (evict > kroom) continue;` (1675), comment confirms the prior `+1e-3` slop
     was removed.
   - **INV-NOSUBMIN:** relabel-into-lower-min forbidden — phase relabel guarded by `mNew >= minMass(tgt)`
     (DECODE fluid path 3094; terrain path 2920; melt path region); empty-refill donor-side gate blocks a
     non-full-drain sub-min refill (`if (|mdot| < minTgt − 1e-3 && !fullDrain) { ++g_submin_refill_gated;
     mdot = 0; }`, 2180–2183); donor σ keeps residual ≥ minMass. **Caveat:** the gate at 2170 applies only
     `!is_gas(MdonorG)` (gases legitimately drop sub-min as they expand) — spec-sanctioned (law #9 gas
     expansion); for cohesive matter it is enforced. See D-MINOR-3 below for the empty-refill relabel.
   - Only `m < ε_mass` relabels to VACUUM with mass+E LEDGERED: `world.boundaryE += droppedE`
     (`engine_b.hpp:2944–2945`), `matIx = void_ix`, mass/momentum/E zeroed — matches §6.4's
     "only m<ε_mass relabels to VACUUM with mass+E ledgered."

8. **Banned symbols (verify #8) — COMPLIANT (clean).** Grep over executable code (comments excluded):
   - `swap_threshold` — ABSENT (only the historical mention in the file-header comment, line 17).
   - `find_chain_hop` — ABSENT entirely.
   - `swap_kv` — ABSENT from code (only comment references confirming its deletion).
   - `own_weight_head`, `p_surf`, `head_relax`, `p_ac_scale` — ABSENT.
   - No `chi`-based swap GATE: `chi`/`is_gas`/`is_compressible` are used only as material-DATA
     discriminators (room, displacement eligibility, empty-refill gas exemption) — never as a swap
     resistance/threshold term. `swap_resistance` (205) reads only `yieldStress` and `minMass`. COMPLIANT
     with law #5 (no state-branch threshold) and the §1.3 retired-symbol list.

### D-MINOR-3 (note, low) — empty-refill relabel does not itself re-check `inMass ≥ min(inSpecies)` in DECODE
- DECODE empty→refill adopts `sNew = (A.inMass > eps_mass) ? A.inSpecies : void_ix` (`engine_b.hpp:3015`)
  with no explicit `inMass ≥ minMass(inSpecies)` check at the relabel site. **Class:** not a bug — the
  guarantee is upstream: the donor-side empty-refill gate (2170–2184) blocks any non-full-drain sub-min
  delivery into a vacuum receiver, so by the time DECODE relabels, a refilled cohesive cell carries ≥ min
  (or the whole mass came from a full-drain). Defense-in-depth is upstream per §6.4 A-10 ("prevented at the
  source, not cleaned afterward"), which is exactly the design. Listed only for completeness.

### D-MINOR-4 (note, cosmetic) — `T_curr` field name is on the §1.3 retired-symbol list
- `C.T_curr[i]` is a live struct field (6 uses; e.g. 2864, 3104). §1.3 lists `T_curr/T_next` among "Retired
  symbols (must not reappear)." **Class:** cosmetic / Module-A concern (state schema), NOT a Module-D
  gate/flux issue. Executable behavior is compliant with law #7: `T_curr` is re-derived every tick from the
  persisted enthalpy `E` (`derive_T(C.E[i], …)` at 2864; comment 3127–3129 confirms it is a within-tick view,
  never a between-tick source-of-truth — no temp-ghost). So it does NOT trip drift-test (c); it is a naming
  collision with the retired symbol. Flagged for the Module-A/F auditor.

---

## Summary

Module D (gates §5 + 5-pass flux §6) is **substantially compliant**. The swap resistance, cadence
(seconds floor), five-pass structure with σ/ρ′/commit, whole-cell flux-XOR, cross-species no-partial-flux,
over-max/sub-min invariants, and the ε-VACUUM mass+E ledger all match the spec, and every banned symbol
(`swap_threshold`, `find_chain_hop`, `swap_kv`, `own_weight_head`, `p_surf`, chi-swap-gate) is absent from
executable code. The two MAJORs are coverage gaps — cross-species lateral movement exists only as a
gas-cell-specific upward-eviction displacement (D-MAJOR-1/2), narrower than §6.3's general "liquid enters a
gas/vacuum neighbor by swapping volumes" and §6.1's "else the lateral face" — and both are spec-tagged
`owner T4` DEBT, conserving, not silent bugs. The two MINORs (R0 priority sorts by raw `drive` not
`drive − R_pair`; missing coordinate-parity tie-break) are low-impact literal-ordering deviations guarded
by the threshold gate and INV-7.
