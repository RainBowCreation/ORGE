# Engine-B v4 — IN-GAME AUDIT CHECKLIST (2026-06-15)

Live build: engine `a2a51cd` / parent `e9cd27d` (rebuild). This is the FIRST in-game run where
radiation / latent / convection / per-gas-EOS carry their real material values (T10b made the
datapack data-live). Goal: confirm each newly-live channel behaves, the rebuild didn't break the
basics, and characterise the one DEFERRED debt (gas-solver, task #11) with real evidence.

---

## 0. Command reference & ground rules

`/orge` subcommands:
- `/orge get <x y z>` → `cell (x,y,z) [material]: <K> K (<C> C), <kg> kg, form=<form>`
- `/orge get-live` → same, for the cell under your crosshair (prints to the action bar)
- `/orge section <x y z>` → section summary: T min/avg/max, mass min/avg/max, non-uniform-cell count
- `/orge set <x y z> <K> [mass]` → set a cell's TEMPERATURE (Kelvin) + optional MASS
- `/orge fill <x1 y1 z1> <x2 y2 z2> <K> [mass]` → same over a box (cell cap applies)
- `/orge debug on|off`

Rules of the road:
- **Material is set by the BLOCK you place** (water bucket → orge:water, lava → orge:lava, ice,
  packed/blue ice, stone, etc.). `/orge set`/`fill` only change T + mass on existing cells.
- **Key temperatures (Kelvin):** water freeze **273**, water boil **373**, lava solidus **1275**
  (→stone), stone liquidus **1450** (→lava), ice melt **273**, steam condense **373**. Gas rest:
  air **288**, steam **373**.
- ⚠ **Display artifact (expected, NOT a bug):** a cell sitting mid-latent-plateau (actively
  boiling/freezing/melting) reports a *non-physical "enthalpy temperature"* through `get`
  (e.g. ~642 K for a boiling water cell). The stored ENERGY is correct; only the displayed K is
  the linearised value. **On plateau cells, judge phase by the block/form + mass, not the raw K.**
- **Sim-time, not real-time:** radiation/conduction budgets below are in *sim minutes/hours*. The
  scheduler advances sim-time per tick, so watch *trends* with repeated `/orge get`, don't expect
  instant results. Leave scenes running and re-poll.
- **Conservation habit:** `/orge section` the scene before & after. Total mass must be constant
  except for blocks you personally place/break. "Mass from nothing" or vanishing = a real bug.
- Record each step as **PASS / FAIL / WATCH** + the `/orge` readout. FAIL → note the exact scene
  for a headless repro. WATCH items (Phase 7) → note *severity*; that evidence decides task #11.

---

## 1. Phase 0 — sanity & baseline

1.1 `/orge debug on`.
1.2 Place a **water** source. `/orge get-live` → expect `orge:water`, ambient-ish K, ~**1000 kg**, liquid form.
1.3 Place **lava**. `get` → `orge:lava`, high K, ~**2650 kg**.
1.4 Place **stone**. `get` → `orge:stone`, ~**2700 kg**, solid/immovable.
1.5 Place **ice** (or blue_ice). `get` → `orge:ice`, ~**917 kg**.
1.6 Aim at open air, `get-live` → `orge:air`, ~**1.2 kg**, ~288 K.
   - PASS: every material reads its v4 §1.2 mass (125–1000 water band, 2650 lava, 2700 stone, 917 ice,
     1.2 air). FAIL: any reads 0/wrong mass or wrong material → datapack/marshaller issue.

---

## 2. Phase 1 — CORE MECHANICS REGRESSION (did the rebuild keep the basics?)

2.1 **Pool & level:** pour a water source onto a flat floor. Expect it to spread and settle to a
    level pool. `/orge section` mid-pool over time → mass redistributes, total constant.
    - PASS: spreads & levels, conserved. FAIL: climbs/over-fills/leaks upward.
2.2 **Connected-vessel equalise:** two adjacent columns, one taller. They should equalise heights
    over time. PASS: levels converge. FAIL: frozen / one arm pumps / overshoot.
    - NOTE: multi-column leveling is intentionally SLOWER than older builds (a fast-but-wrong defect
      was deleted). Slow-but-correct = PASS. Genuinely-stuck = FAIL.
2.3 **Fall into air:** drop water from a height into open air. PASS: it falls, lands, fills; **no
    floating residue / 0-kg ghost / air trail**. FAIL: residue or ghost cells.
2.4 **Immiscible displacement:** pour lava onto a body of water. PASS: lava **sinks under** water by
    swap (no lava→stone-on-contact, no water→nothing); per-species mass conserved. FAIL: conversion
    or fabrication.

---

## 3. Phase 2 — RADIATION (T6, newly live: ε now non-zero)

3.1 **Open-air lava crust:** place a single exposed lava block with sky/air above. Poll
    `/orge get <pos>` every ~30 s real. Expect T to **fall** through ~1373→1275 K, then the surface
    cell **relabels to stone** (crust). Budget ≈ tens of sim-min per exposed face.
    - PASS: T monotonically falls, eventually → stone. FAIL: T frozen, instant freeze, or T *rises*.
3.2 **Lava|water quench:** place lava directly beside water. Poll the water cell. It should **heat
    toward 373 K** (then boil — see Phase 3) while the lava cools. Budget: water → 373 K in ≤ ~45 sim-min.
    - PASS: water heats to boil within budget, energy conserved (lava loses what water+steam gain).
      FAIL: water never heats / heats with no matching lava cooling (energy fabrication).
3.3 **Sealed-roof shutoff:** lava in a pocket with a SOLID ceiling and only a thin trapped-air gap.
    Radiation into the trapped air should saturate and **shut off** (~20 ticks), after which lava
    cools only at the slow conduction rate.
    - PASS: trapped air stops heating (no perpetual climb); lava cools slowly. FAIL: trapped air
      heats without bound (→ this is the Phase-7 gas debt leaking in; note it, don't fail T6 for it).

---

## 4. Phase 3 — LATENT / PHASE CHANGE (T7, newly live: latent_heat now non-zero)

4.1 **Boil (water→steam):** pick a water cell, `/orge set <pos> 500` (drive it hot), or leave it
    beside lava from 3.2. Watch: it should **PIN around the boil point while latent heat is paid**
    (the `get` K will read the ~642 K enthalpy-artifact during the plateau — expected), then
    **relabel to steam** at ~1000 kg (in-band). 
    - PASS: pins, then becomes steam; per-species mass conserved (1000 kg water → 1000 kg steam).
    - ⚠ EXPECTED LIMITATION: the steam may **NOT violently expand** — with the deferred gas-solver
      (#11) it may instead sink/relocate or settle rather than exploding outward. That is the KNOWN
      gas gap, **not** a T7 failure. Note how it behaves (Phase 7).
4.2 **Freeze (water→ice):** `/orge set <water pos> 250`. Watch: pins ~273 K paying latent, then
    **relabels to ice@917 kg and EVICTS the surplus ~83 kg** to a legal neighbour (or DEFERS if
    there's nowhere). 
    - PASS: becomes ice, **no cell ever reads >917 kg ice** (no over-max), the 83 kg shows up in a
      neighbour, total conserved. FAIL: ice cell over 917 kg, or 83 kg vanishes/fabricates.
4.3 **Melt (ice→water):** `/orge set <ice pos> 300` → pins ~273, then → water.
4.4 **Condense (steam→water):** cool a steam cell below 373 (`/orge set <steam pos> 350`) → pins,
    pays latent, → water. (T10b fixed steam's latent side — verify the plateau actually happens, not
    an instant flip.)
4.5 **Rock cycle:** heat stone above 1450 K (`/orge set <stone pos> 1500`) → melts to lava; the
    Phase-3.1 crust covers lava→stone.
    - PASS for 4.3/4.4/4.5: each transition pins at its threshold (latent paid), conserves, T
      continuous across the flip (no wild jump except the deliberate stone↔lava 175 K hysteresis).

---

## 5. Phase 4 — CONVECTION (T9, newly live: β now non-zero)

5.1 **Hot-under-cold overturn:** build a tall (≥4) water column. `/orge set` the BOTTOM cell to
    ~**350 K** and the top cells to ~**290 K** (heavy-cold over light-hot). Watch the temperatures.
    - PASS: the hot (lighter) water **rises / the column overturns and mixes** over time. FAIL:
      stratification stays frozen forever (no convection = β not reaching the swap gate).
5.2 **Control (no spurious churn):** a uniform-temperature water column should **stay put** (no
    overturning, no mass jitter). PASS: bit-stable. FAIL: churns with equal temps (spurious).

---

## 6. Phase 5 — PER-GAS EOS (T10, newly live: T_ref_gas now set)

6.1 **Air pocket compression:** trap an air cell under ~2 m of water (e.g. air gap capped by water).
    `/orge get` the air cell. Expect mass to settle around **~1.2× its rest** (compressed by the
    overburden), pressure ≈ the water column weight, then **stable**.
    - PASS: compresses to ~1.2× and holds. FAIL: packs without bound, or vanishes, or oscillates wildly.
6.2 **Deeper = more compression:** repeat under more water depth → more compression, still **bounded**
    (never infinite pack, never sub-min vanish).
6.3 **Steam over-pressure:** a freshly boiled steam cell (from 4.1) reads a very high pressure; it
    should push on neighbours over ticks, bounded. (Expansion may be limited by #11 — note it.)

---

## 7. Phase 6 — SWAP / BUOYANCY (T8)

7.1 **Lava sinks under water** (immiscible swap, no conversion) — re-confirm 2.4 at rest: a lava
    layer under water stays sorted heavy-down.
7.2 **Air rises through water:** release an air cell at the bottom of a water column → it rises.
7.3 **Cadence sanity:** overturns happen at a steady *rate* (not instant teleport, not never).
    - PASS: sorting is correct and gradual. FAIL: instant snap, or stuck unsorted.

---

## 8. Phase 7 — KNOWN-DEFERRED DEBT — **WATCH & CHARACTERISE, do NOT fail the build**

These are the user-deferred gas-solver (task #11) symptoms. They are bounded + mass/energy-conserving.
Your job: record **severity**, because this evidence decides whether #11 is worth building now.

8.1 **Resting thermally-graded gas self-heating:** leave a still air region next to a heat source
    (e.g. above lava) for a while. Poll `/orge get` on a static air cell.
    - WATCH: does its temperature slowly creep UP with nothing moving? How fast? Does it heat its
      neighbours noticeably? Is it visible/annoying in normal play, or negligible? **Record numbers.**
8.2 **Gas conveyor-packing:** a tall air column may slowly pack/redistribute spuriously (barometric
    overturn without the proper solver). WATCH: does a sealed air column drift in mass over minutes?
8.3 **Boiling-cell display T (~642 K):** confirm it's only the *displayed* K on plateau cells; the
    block/phase/mass behave correctly. (Display-only; note if it confuses any in-game readout.)

---

## 9. Phase 8 — WHOLE-SCENE CONSERVATION

9.1 Before a big multi-material scene: `/orge section` each section, sum the masses.
9.2 Run the scene for a while (boil, freeze, convect, displace).
9.3 Re-sum. Total mass must equal the start (± your own place/break). Per-material totals should only
    change by genuine phase transitions (water→steam etc.), not leak.
    - PASS: conserved. FAIL: net mass appears/vanishes → headless-repro the scene.

---

## Reporting back

For each numbered step: **PASS / FAIL / WATCH** + the `/orge` readout(s). 
- Any **FAIL** → capture the exact block layout + `/orge` commands so it can be reproduced headless,
  fixed test-first, conserved, adversarial-reviewed, pushed.
- **WATCH** (Phase 7) severities → these feed the **go/no-go on task #11** (the deferred semi-implicit
  gas mass-transport solver). If 8.1/8.2 are negligible in play, #11 stays deferred; if they're
  visibly bad, that's the evidence to build it.
- Spec-wording amendments still awaiting the user's pen are unrelated to this audit
  (`notes/2026-06-14-way2-flux-implicit-proposed-amendments.md` + the §1.3 `ω·(1+α_eos)<2` strike).
