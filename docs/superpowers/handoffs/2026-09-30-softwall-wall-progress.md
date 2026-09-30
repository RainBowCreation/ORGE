# Handoff: soft-wall restoring force, progress (2026-09-30, evening)

This continues `2026-09-30-softwall-walls-and-universal-yield.md` (Task A, the restoring wall; Task B, universal
yield). **Read DESIGN-LAW.md first, verbatim.**

## State

- The engine work lives on the ORGE-ENGINE branch **`softwall-wall` @ 1c3e00f** (local only, not pushed, not
  merged). Base: `rebuild` da0540e.
- The ORGE superproject is unchanged: no submodule bump and no `liborge.so` rebuild yet.
- Task A is mostly done. The open gap is venting into vacuum (below). Task B has not started.

## User's design (agreed, do not re-litigate)

The pressure is **one curve per cell, p(m)**:
- inside `[min, max]` it is the real pressure law, unchanged;
- outside the window it is steep (the user's sketch: an S-curve that goes near-vertical past min and max).

The wall only raises or lowers the cell's pressure. Normal force and flow move the mass. There is **no extra
evict pass or special rule**. The user rejected generalizing `freeze_evict`.

Steepness comes from real physics, not from asking the user: K = bulk modulus / density ≈ **2.2e6 Pa/kg**
(water 2.2e9/1000; lava and stone are 4e6–2e7). The LUT has no bulk-modulus column (adding one is a law #8
change), so it is one global value, `Globals::k_wall`.

## What's implemented (`core/engine_b.hpp`)

`wall_pressure(snap, mats, G, dt, cx,cz,x,y,z)` is defined just before `relax_pressure_world`:

```
over  = max(0, m − max);   under = (1−χ)·max(0, min − m)
v_out = min(1, room/over)     room  = Σ same-species neighbours (max_j − m_j)·mobility
v_in  = min(1, spare/under)   spare = Σ same-species neighbours (m_j − min_j)·mobility
W     = K·(over·v_out − under·v_in) / (1 + K·A²·dt²/V)
```

- **Read only by the §4 force** in the resolve_world DriveCtx pre-pass. The cell pressure is `P_i + W_i`:
  - interior faces use `½(P_i+W_i + P_j+W_j)`;
  - boundary ghosts (`liqf`) use `P_i + W_i`.
- **Relaxation is untouched.** Putting W into the relaxation drifted in sealed boxes, and blending it as an anchor
  pulled deep cells below hydrostatic.
- **Implicit, backward-Euler form** (same idea as `eos_impulse_theta_v4`). At a real K the explicit wall was 110 MPa
  and slammed the cell to the CFL cap: 2700 → 578 kg and a one-time +29 MJ of heat. Implicit, it moves about the
  surplus per tick.
- **Room-scaled by `v_out`/`v_in`** (law #9: "no escape = no-op"). Without this, a full lava pool under air blew
  the air column up at **9.9 MJ/tick** of heat created from nothing. With it the drift is 14 J/tick, the same as
  with the wall off.
- In-window W = 0 exactly, so every in-window scene is bit-identical.
- The stale "DEFERRED" comments in DECODE (~3428, ~3597) and `engine_b_softwall_relabel_test` are updated.

## Evidence

**New test `tests/engine_b_softwall_wall_test.cpp`** (in the CHEAP list): ALL PASS.

| Scene | Result | Energy drift |
|---|---|---|
| (a) 2700 lava \| 2000 lava | sheds to 2633 (≤ max) | 0 J/tick |
| (b) 2700 or 3050 lava \| vacuum only | rests; asserts only no pump + mass exact | 0 J/tick |
| (c) \| full lava | rests | 0 J/tick |
| (d) sealed alone | rests | 0 J/tick |
| (e) in-window scene, wall on vs off | bit-identical | — |

**Ad-hoc probe `ORGE-ENGINE/build/probe_air.cpp`** (untracked, a 5×3 lava pool in a stone basin under air):

| Pool | Result | Energy drift |
|---|---|---|
| full + one 2700 cell | rests | 14.4 J/tick |
| partial + one 2700 cell | sheds | 13.9 J/tick |
| baseline, K = 0 | — | 13–15 J/tick |

**Native cheap tier:** **no new reds**. It has the same 10 failing tests and identical FAIL lines as
`ORGE-ENGINE/build/BASELINE_REDS_81c28b2.txt`. The log is in `ORGE-ENGINE/build/cheap_wall.log`.
- `engine_b_inv_universal_test` (the no-branch guard) passes.
- Not yet run: `quick` and `full` tiers, and the Java `rtdd` loop.

## NEXT: over-full lava must expand into vacuum (user wants this)

**Today:** 2700 lava next to vacuum only rests (it falls into vacuum *below* by gravity, but not sideways or up).

**Why:**
1. `wall_pressure` counts **no** room in vacuum, a deliberate stopgap. When vacuum was counted, the push could not
   get through (reason 3), so the cell kept accelerating in place and leaked 1.9 kJ/tick of heat.
2. The §4 vacuum face uses the law #2 own-P ghost (`pbar = liqf`), so the wall cannot push into vacuum. A tried fix,
   `pbar = liqf − wall_i` ("vacuum can't push back"), did make the cell move.
3. The flow rule blocks it. The §6.4 empty-refill gate (`engine_b.hpp` ~2621–2640, `g_submin_refill_gated`) blocks
   any flux into an empty cell below `(1−χ)·minMass` (lava 330) unless the donor full-drains. Flow into vacuum only
   reaches that amount slowly:
   - the face drive is `½(w_i + 0)`, half the donor velocity, because the vacuum's w is 0;
   - it lags one tick (the liquid drive uses the pre-force velocity);
   - vel_damp and the BUG-A liquid bleed cap it.

   Measured: the 3050 cell's velocity settled at 0.83 m/s, but 330 kg needs 0.87.

**Agreed direction (the user confirmed the physics: the steep pressure must force it to spread):**
- Count vacuum as room again in `wall_pressure` (`empty · M.maxMass`, same-species room).
- Vacuum face: `pbar = liqf − wall_i`. This deviates from the law #2 wording, so note it for the user; the wall is
  not hydrostatic, so the ghost should not mirror it.
- Empty-refill gate: when a flux into an empty cell starts and is below min, **round it up to one min-chunk** if the
  donor's remaining budget stays ≥ its own min. Result: 2700 → 2370 + 330.
  - This is the reverted `7ea2431` ("minMass-quantum refill"). Read that diff; it is roughly 29 lines at the empty-refill site.
  - It was reverted because lava overflowing a ledge fragmented into sub-min droplets, and
    `engine_b_resolve_invariants_test` §5.2 case C went red. **Fix that side effect**; don't just re-apply the commit.
  - This also fixes the old in-game bug "lava won't flow into a broken (vacuum) cell".
- Then re-add the test (b) expectations:
  - 400 over next to vacuum sheds to ≤ max, the receiver is in-window, drift < 150 J/tick;
  - 50 over next to vacuum: the donor can spare 330 (2700 − 330 = 2370 ≥ min), so it should shed too.
- Re-run the wall test, `probe_air`, and the cheap tier against the baseline.

## After that

1. Run the `quick` or `full` native tier, rebuild `ORGE-ENGINE/native/build_liborge.sh`, and copy the library to
   `core/src/main/resources/natives/linux-x64/liborge.so` (gitignored, so use `git add -f`).
   - Run the Java `JAVA_HOME=$HOME/.jdks/dragonwell-21.0.11 rtdd run`, then `./gradlew :core:check` before merge.
   - Merge `softwall-wall` into the engine's `rebuild` and bump the submodule. The user decides on push.
2. **Task B (universal yield)** per the original handoff:
   - `σ_i` = the own-wall term. Use `wall_pressure` before the implicit divide, i.e. `K·over·v_out`, because
     `K·over` is the stress. The handoff's "σ = own wall term only" option works now that the wall exists.
   - Yield weight `yf = max(0, σ − τ_y)/(σ + ε)`. It gates W itself, i.e. `W·yf`, so ice extrudes only while the
     wall exceeds `τ_y`.
   - The rest of the list (wall_weight, relax frozen, DECODE momentum, Nusselt, `movable()`, data) is unchanged.
3. Open question to surface (not blocking): K is one global value. Per-material stiffness needs a law #8 LUT column
   (bulk modulus), or it can be derived from existing data.

## Gotchas

- The machine was loaded by other jobs (lune `power_fit`), so the cheap tier took more than 10 minutes. Run it in the
  background and grep the log for `FAIL|COMPILE|TESTS FAILED`. Never trust a piped exit code.
- `sed -i` in `core/` and `tests/` prints "preserving permissions … Operation not permitted" but still writes. Check
  with `git diff`.
