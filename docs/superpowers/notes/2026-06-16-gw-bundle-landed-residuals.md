# GW-1 + GW-2 + #11 LANDED — status & tracked residuals (2026-06-16)

**Landed to `rebuild`:** engine `04b368a` / parent (this commit). Bundle = GW-1 (gas force driven by the single
relaxed `P`, retire A-8 EOS gauge anchor from the force) + GW-2 (`N_relax` cap [1-8]→[1-32]) + #11 (§4
force-region backward-Euler settling-damp on the gas y-rest impulse). User-ratified; landed via worktree
`gw1-gw2` / `gw1-gw2-engine`, parent-verified independently before merge.

## What this FIXES (independently verified on the rebuilt `.so`)
- **In-game bug #2 (tall-column air-churn) CURED:** `atmos_probe` UNIFORM-1.2 (H=200, 5000 ticks) — osc 0.1%,
  bottom 1.02× rest, top holds at floor, mass exact 240.000/tick. (Was: bottom churns 95×, top→0.0006.)
- **LAW #1 strengthened:** the gas §4 force reads ONLY the relaxed `P` (the per-cell EOS gauge anchor is gone
  from the force; EOS now enters only as the §3.1 `α_eos` relaxation source). Two gas pressure representations
  collapse to one.
- **Sealed INV-GAS pocket:** 1.2020× rest (closed-form 1.4407 kg, 0.11% err) — un-regressed.
- **Cold-rest self-heat:** `inv_al` rest-cold E-drift 258,508 → 2,458 J/tick (105× better).
- **All hard invariants preserved:** liquids byte-identical (INV-P1 75005/155004, INV-P2 [4172.67|3339.33]),
  determinism (inv7), conservation (grand + per-species mass + grand E exact). Java ITs green
  (ResidentLutParityIT golden regenerated for the GW-1 gas-force change — conservation-exact; EnthalpyRestore,
  EngineBVelocity green).

## TRACKED RESIDUALS — continued #11 gas-solver (NOT fixed here)
All three share ONE root: **the resting gas `P` converges flat (≈ local EOS anchor), not the true hydrostatic
ladder `ρg(k−½)dx`** — the column rests cold via the #11 force-region support term, not via `−∇P` balancing
gravity. The relaxation hydrostatic-ladder fix (the "V4" attempt: gas faces read `P[j]` + one-sided α_eos
source) was tried and **rejected** because it regresses the sealed pocket (1.2×→2.67×) and only reaches the
ladder at `N_relax=32`. So the ladder rebuild is deferred to the deep gas-solver (#11), needing a relaxation
rebuild that does NOT starve the pocket.

1. **G1 hydrostatic ladder flat** (`engine_b_atmos_ladder_test` G1): converged open-column gas `P` ≈ 9.4 Pa
   vs ideal 186; `dP/dy ≈ 0`. Cold rest holds (G2 green) but via the support term, not a real `∇P`.
2. **Full-chunk open atmosphere** (`engine_b_inv_al_smoke_test` rest-cold): `maxAirU` still 1.333 on boundary
   cells (E-drift down 105× to 2,458 J/tick, but velocity rail persists). Rested cold at base 726bae5; GW-1
   regressed it; #11 reduced the heat but not the rail. The narrow column (atmos_probe) IS fixed; wide
   geometry is not.
3. **Barometric top boundary** (`atmos_probe` BAROMETRIC): top cell 14.7% deviation (cap 0.1%) at t≈1723;
   conservation fine. Open-top boundary destabilizes.

Also still separate/pre-existing: the `inv_al` **walled-cavity** (interior solid|gas boundary) rail
(T4b-OPEN), and the leveling debt (`horizontal_flow`/`cohesion_settled`, T4/T5).

## RATIFICATION TODO (user-pen)
The #11 §4 force-region BE-damp is documented PROPOSE-ONLY in
`notes/2026-06-16-gas-coldrest-force-region-be-damp-proposed-amendment.md`. The user ratified it by landing
the bundle, but the formal edit of `DESIGN-LAW.md` / spec §4 to enshrine the mechanism is user-only and still
pending. GW-1/GW-2's spec edits (§2.1 supersede A-8, §1.3/§3.1 N_relax, §11 INV-ATMOS) already landed in
`77e5aed`.
