# Engine-B Stage-1 (Force→Advect) — IN-GAME AUDIT CHECKLIST (the gate before Stage 2)

**Status:** Headless gates GREEN + pushed (engine `b66801c` / parent `7cff241`, both `origin/rebuild`).
The in-game audit below is the REAL gate — headless cannot catch the in-game-only bugs. Stage 2 does
NOT start until a human runs this audit and signs off (or files findings to carry forward).

## What shipped (Stage-1 mechanical core)
The live JNI path (`orgeStepWorld` → `step_world_b(World&,mats,double)` → `stepForceAdvect`) now runs the
new **Force→Advect** core in `core/force_advect.hpp`, replacing the `E=ρ·h·w` energy-vector advection on
the live path (the old energy-vector pipeline survives only behind the explicit-`Globals` overload for
legacy unit tests). Conduction is unchanged (still its own `PASS_CONDUCTION`).

- **Pass A (`forcePass`)** — per cell: `F = gravity + −(1/ρ)∇p (local EOS) + ext`; branchless Bingham
  `a_eff = max(0,|F|−yield)·F̂`; integrate + viscous drag; CFL clamp. Writes ONLY velocity.
- **Pass B (`advectPass`)** — the sole conservation pass: antisymmetric upwind face flux driven by
  `½(v_i+v_j)·n̂`; donor-budget + receiver-room clamps; carries `ΔE = ṁ·cp·T` and `Δp = ṁ·v`; vacClaim
  foreign-species block + vacuum room-cap (claimed-species maxMass); cross-species PE-swap (permutation).
- `yieldStress` added to `Material` (0 = pure fluid).

## Headless gates already GREEN (evidence)
- Engine cheap tier 16/16 + heavy `correctness` 12/12 (NOT the long stress soak — human-gated this run).
- `force_advect_soak_test` 400 steps, BOTH scenarios: grand mass / per-species mass / max_mass / energy /
  finiteness strict every step; Scenario 1 water actually FALLS to floor; **Scenario 2 #7 columns LEVEL
  to gap 0** (strict ≤1).
- Java `:core:test` 395/0/0 + `:core:integrationTest` 24/0/0 (skipped=0, real `.so`); both loaders build.

## IN-GAME AUDIT — run these (the gate)
1. **1 bucket falls, bounded, conserved.** Place one water bucket high over a floor. Expect: it falls,
   rests on the floor, does NOT explode/vanish, stays bounded; total mass constant (use `/orge` to probe).
2. **#7 leveling (the keystone scenario).** Two adjacent same-species water columns of different height
   (e.g. a 4-tall beside a 1-tall). Expect: they equalize to within ~1 cell over time (this now works
   headless — confirm it works in-game with the real material LUT and scheduler cadence).
3. **Lava sinks under water (cross-species swap).** Lava placed above water should sink below it
   (heavier → lower), water rises; neither species' total mass changes, no conversion to stone/steam.
4. **Water near lava with a gap (forbidden-class regression watch).** Put water and lava both adjacent to
   an empty cell / thin gap. Expect: NO mass fabrication, NO atmosphere-eating, per-species totals stable
   (two such bugs were caught+fixed headless this stage — verify they don't reappear in-game).
5. **Vacuum convergence (no overshoot).** Open a void pocket surrounded by fluid on several sides. Expect:
   it fills WITHOUT any cell exceeding its max_mass (no 2000–3000 kg cells).
6. **Cross-chunk flow.** Water flowing across a chunk border into air: it crosses (slowly — see note) and
   conserves. Note: same-height cross-species into air is vacuum-mediated and SLOW by Stage-1 design
   (full cross-species displacement is banked Stage-2+). If it feels too slow in-game, file it.

## Known Stage-1 limits / carry-forward (not bugs to "fix" in the audit — note if they bite)
- **Cross-species at the SAME height is a no-flux interface** (cross-species moves only via vertical
  PE-swap or vacuum-fill). Horizontal water-into-air is therefore slow (vacuum-mediated). Banked.
- **Conduction is the OLD pass** (Stage 2 moves it into the new core as Pass C + the D9 heat multiplier).
- **No int16 velocity persistence yet** (D4, Stage 4) — velocity is float in the live World.
- **Calibration (K, γ, α, μ_ref, yield_ref) is provisional** (Stage 5).
- **`Fmag > eps_mass` Bingham guard** is a kg-vs-N unit label mismatch (harmless now; fix at calibration).

## After the audit
File any in-game findings as the first bugs for Stage 2. Then the Stage-2 handoff (Pass C conduction +
D9 multiplier) gets written carrying those forward. See spec §3 Pass C and the Stage-1 plan's Stage roadmap.
