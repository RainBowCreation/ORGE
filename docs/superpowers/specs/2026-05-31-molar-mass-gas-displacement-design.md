# §11 — Vacuum + finite compressible gas (molar-mass displacement) — Design Spec

**Mandate (user).** Liquid flow currently *creates mass from nothing*: water poured into N cells ends at
`1000 + (N−1)·1.2` kg. Root cause — horizontal wetting **relabels the destination air cell's ~1.2 kg as
water** instead of displacing it ([[orge-engine-cross-section]] wetting path; [[orge-air-sink-fix]] gave
air a real 1.2 kg). The fix the user chose is a **physical model**, not a local patch:

1. **Gas is finite — including air itself.** Air is never created from nothing and never consumed. It is
   only **pushed, compressed, or expanded**.
2. **Breaking a block makes VACUUM (vacuum = 0 mass), not air.** Removed-block cells become the empty
   sentinel; real gas flows in from neighbours to fill them (conserving total gas).
3. **One comparator — density.** Every cell is exactly **1 m³**, so *mass-per-cell IS density*. Denser
   sinks, lighter rises/gets pushed away. This unifies fall, buoyancy, liquid sorting, and gas
   volume-filling under a single rule. *(User: "high molar mass sinks to the bottom and pushes the lower
   molar if it wants to spread.")*
4. **Gas is very compressible.** Air: `min_mass = 0.001 kg` (1 g, near-vacuum), `default_mass = 1.2 kg`
   (1 atm rest), `max_mass = 1000 kg` (compression cap).
5. **Strictly conserve every species, air included.** The only untracked thing is **vacuum** — the absence
   of a species. Drop the §9 "air-credit" and the "air untracked" exemption.

This pulls forward the "compressible-gas track" that [[orge-fluid-flow-phase2a]]'s §10 design banked as
*future*, and it is the principled end of [[orge-air-displacement-fix]] (which fixed only vertical fall).

---

## 1. The core reframe: VACUUM vs FINITE GAS

The engine has always had a zero-index **void sentinel** (`matIx==0`, mass ≤ `ADV_EPS_MASS`). Until now
that sentinel was conflated with *air*, and a parallel `lut.air` flag let fluids "fall/wet INTO and adopt"
air — i.e. **discard** it. That discard is the bug once air carries 1.2 kg.

Reframe, crisply:

| concept | engine representation | mass | created by | behaviour |
|---|---|---|---|---|
| **Vacuum** | `matIx==0` OR `mass ≤ ADV_EPS_MASS` | 0 | breaking a block; total evacuation of a gas cell | not a species; gas expands into it, liquid falls into it |
| **Air** | real LUT material, `state=air` | 0.001 … 1000 (rest 1.2) | world-gen seeding only (then persisted) | finite, conserved, compressible gas |
| **Steam** | real LUT material, `state=gas` | (rest 0.6) | §7 phase change (water→steam) | finite, conserved, buoyant gas |

**The `lut.air` "empty/adopt" flag is removed.** Its job splits:
- "a cell a fluid may move into" → now means **vacuum OR a strictly-lighter fluid** (the density rule).
- "the ambient gas" → air is just a normal gas material; nothing special in the kernel.

Vacuum and air are **visually identical** (both passable, both below the 125 kg render floor), so no
in-game visual change — only the mass bookkeeping differs.

---

## 2. Molar-mass data (already in the datapack — thread it to the engine)

`molar_mass` (kg/mol) is **already parsed** by `MaterialCodec`/`Material` but **never reaches the kernel**
(`MatLUT` has no molar field). It is the physical source of every gas density and the gas sort key.

### 2.1 Roster (current `molar_mass`, kg/mol)

| material | state | molar_mass M | default_mass ρ₀ | min_mass | max_mass | notes |
|---|---|---|---|---|---|---|
| **air**  | air   | **0.029** | 1.2  | **0.001** *(new)* | **1000** *(new)* | the ambient finite gas |
| **steam**| gas   | **0.018** | 0.6  | 0.6 (= rest, today) | 0.6 → **raise** so steam can compress/expand* | lighter M than air ⇒ rises |
| **water**| fluid | 0.018 | 1000 | 125 | 1000 | condensed; incompressible |
| **lava** | fluid | 0.060 | 3100 | 400 | 3100 | condensed; incompressible |

\* Steam's `min_flow_mass == max_mass == default_mass == 0.6` today (a fixed-mass gas). To let steam obey
the same compressible-gas rule as air it needs `min_mass < default < max_mass`; pick in the C++ reference
sim. (If we keep steam fixed-mass for now, document it as an explicit Phase-A exclusion — air is the
proving ground.)

### 2.2 Why molar mass — ideal gas grounds the densities

For a gas cell of mass `m` (kg) in `V = 1 m³` at temperature `T` (K), moles `n = m/M` and
**pressure `P = n·R·T / V = m·R·T / M`** (R = 8.314 J/mol·K). The resting densities fall straight out of
`ρ₀ = P₀·M / (R·T₀)` at `P₀ = 101325 Pa`:

- air `(M=0.029, T₀≈293 K)` → ρ₀ = **1.205 kg/m³** ✓ (= `default_mass` 1.2)
- steam `(M=0.018, T₀≈373 K)` → ρ₀ = **0.588 kg/m³** ✓ (= `default_mass` 0.6)

So the datapack is already internally consistent. Molar mass is what makes the model physical rather than
a pile of magic densities.

### 2.3 How the engine uses molar mass

- **Sort/buoyancy key = density = current cell mass** (1 m³). At gas equilibrium (equal P, T) density
  ∝ molar mass, so **"higher molar mass sinks"** emerges automatically — air (M 0.029) sinks under no
  gas, steam (M 0.018) rises through air.
- **Pressure-driven flow/compression (Phase B, §8) uses `P = m·R·T/M`**: gas flows high-P → low-P,
  expanding into vacuum, compressing when crowded, and — because heating raises P — letting **hot gas
  shed mass and become buoyant** ("hot air rises"). This is the only place `T` enters gas motion, and it
  is *impossible without molar mass*.

**Plumbing:** add `molarMass` to `Material` LUT export → `MatLUT.molar` (a new `const float*`) → JNI
`step()` arrays → `orge_kernel.hpp` / `sim_engine.hpp`. No ABI *struct* break for callers other than the
new array (the JNI already passes per-material arrays; add one). Bit-identity preserved.

---

## 3. Air material def changes (datapack)

`air.json` gains the compressible range and keeps its molar mass:

```jsonc
{
  "thermal_conductivity": 0.026,
  "heat_capacity": 1005,
  "default_mass": 1.2,
  "molar_mass": 0.029,
  "min_flow_mass": 0.001,   // 1 g floor — air can be drawn to near-vacuum
  "max_mass": 1000,         // compression cap
  "state": "air"
}
```

`MaterialCodec` already defaults `max_mass→0 (=default)` and `min_flow_mass→0`; the gas crash-guard
("`state=gas` requires `min_flow_mass>0`") must be **extended to `state=air`** (an air cell with a 0 floor
+ huge cap is fine, but `min_flow_mass>0` keeps the kernel's donor floor well-defined). Confirm `state=air`
participates in advection (today only `fluid`/`gas` set `lut.fluid=1`; **air must now set `fluid=1`**).

---

## 4. The unified displacement rule (native physics)

Three drivers, all at the advection cadence, all comparing **current cell mass = density**:

1. **Liquid gravity.** Fall into the cell below + viscosity-limited horizontal surface leveling among
   same-species liquid cells (Phase-2a, kept). A liquid falls/levels into **vacuum** or a **strictly
   lighter fluid** below/beside it.
2. **Gas volume-filling (pressure leveling).** A gas equalizes its mass across all connected
   non-solid cells in **all 6 directions** — expanding into vacuum and lower-mass gas, compressing toward
   `max_mass` when crowded. This is what fills a broken-block vacuum and what gets squeezed when a liquid
   floods in. (Phase A: equalize mass directly; Phase B: equalize **pressure** `m·R·T/M`.)
3. **Density swap (buoyancy / sorting).** Between two vertically-adjacent cells whose ordering is
   inverted (lighter below heavier, beyond a hysteresis threshold) → **full-cell swap** toward stable
   ordering, conserving each species' mass and carrying enthalpy. Drives fall, steam-rises-through-air,
   air-rises-through-water, liquid sorting.

**Displacement, never consumption (the bug fix).** When a liquid moves into a cell holding a *lighter*
fluid (gas/air), that gas is **swapped out** (it rises / compresses into neighbours via rules 2–3), not
absorbed. `1000 kg → N cells → total stays 1000.0`; the air it pushed is still present, elsewhere.

**§7 reacting pairs still excluded** — water+lava is owned by §7 / the vanilla whitelist
(→ obsidian/stone/cobblestone), never density-sorted. Unchanged in spirit from Phase-2a Decision 7.

---

## 5. Block-break → vacuum (Java track)

Today a removed block reseeds its ORGE cell toward the new block's material — for air-replacement that
seeds **1.2 kg of air from nothing**. New rule: **a block removed to air/nothing sets the cell to VACUUM
(mass 0, the void sentinel)**, and the gas rule (§4.2) refills it from real neighbours.

- Hook the block-change path (both loaders) feeding `MaterialChangeReseed` / the reconcile signature:
  when the post-edit block is air (or the broken cell has no fluid material), write `mass=0, mat=void`,
  **not** `default_mass` air.
- This is the same class as [[orge-reseed-misfire-fix]]: record the signature from the **engine output**
  so the reconciler's own vacuum-fill placements aren't mistaken for player edits.
- **Placing** a solid block where gas was: the gas in that cell must be displaced into neighbours
  (compression). If neighbours are all at `max_mass` (sealed + saturated) the gas has nowhere to go —
  rare; document as a hard edge (the place still succeeds; conservation may hold the batch that cycle).

---

## 6. §9 — per-species conservation including air

- Air becomes a **tracked, conserved species**. Sum mass per species index (the existing single O(N)
  accumulator, [[orge-engine-cross-section]] batch ledger) — **air included** — each conserved within
  `ε·ΣN`. **Delete the air-credit and the "air untracked" exemption.**
- **Vacuum contributes 0** to every species sum (it is no species).
- **Per-cell bound** stays per-section: a gas cell may hold up to its `max_mass` (air → 1000); a cell over
  its own cap (except the documented §7 over-cap boil parcel) still fails immediately.
- **§7-transitioned cells exempt** (phase change is a legitimate species source/sink), unchanged.
- A non-conserving batch HOLDS (no partial write) — the existing safety net; with displacement-not-consume
  it should essentially never trip on flow.

---

## 7. Co-stepping the gas column

Strict conservation needs a **loaded receiver** for displaced/rising gas. Extend the co-step set
([[orge-scheduler]] `SeamCoStep`): a flow-active section co-steps not only its 6 face-neighbours but,
specifically, the section **above** an active fluid/gas surface, so rising air/steam has somewhere to go
across the Y seam. A dormant air column above a flooding pool must wake to receive — else strict
conservation would stall the flow at the seam. Calm gas settles back to sleep via `noteSettle`.

---

## 8. Compressibility & ideal-gas pressure (phased)

- **Phase A — density model (correctness, ships the bug fix).** Gas equalizes **mass** across connected
  cells (volume-fill), compressible between `min_mass`…`max_mass`; buoyancy/sort by current mass; air
  conserved; break→vacuum. Temperature does **not** yet drive gas motion. This alone makes
  `1000 → 1000.0` and air finite.
- **Phase B — pressure model (richness).** Replace "equalize mass" with "equalize **pressure**
  `P = m·R·T/M`": gas flows down the pressure gradient, so heating a gas cell raises its P and it expands
  / sheds mass / becomes buoyant ("hot air rises"; convection). Uses the per-cell temperature already in
  the store + the molar mass threaded in §2. Anti-oscillation: hysteresis on the P threshold + one
  move/cell/step claim (reuse the swap guards).

Phasing keeps the first increment small and test-anchored; molar mass is **plumbed in Phase A** (data) and
**used for motion in Phase B**.

---

## 9. Invariants (non-negotiable)

- `orge_kernel.hpp` stays **bit-identical** to `sim_engine.hpp` (`advection_parity_test`, extended with
  vacuum-fill / compress / displace / molar cases) — same commit.
- **Mass conservation** across the whole region every cycle, **air included** (the headline test:
  `1000 kg → spread to N cells → total == 1000.0`; `break block → vacuum → air refills → Σair unchanged`).
- §7 water/lava reaction excluded from sorting; vanilla suppressor still whitelists §7 contact.
- **Render floor 125 kg** unchanged — vacuum and resting air both render as passable empty (no visual
  regression).
- **Dormancy** preserved — settled gas/liquid sleeps; only the active interface + co-stepped skirt steps.
- JNI grows one per-material array (`molar`); no behavioural ABI break for existing callers.

---

## 10. Consequences the user accepted

- **Mining slightly thins the air.** Breaking a block adds open volume; finite air spreads into it from
  neighbours, lowering local density a hair (and globally over much mining). Intended realism.
- **Sealed pockets resist flooding.** Water can't flood a fully sealed air pocket — trapped air compresses
  toward 1000 kg then resists. A sealed vacuum stays vacuum until something connects.
- **Air is seeded once at world-gen** (new chunks: 1.2 kg/cell) and **persisted per-cell** thereafter by
  the section store — reloaded terrain keeps whatever air it had; only brand-new terrain gets fresh air.

---

## 11. File-touch map

| file | change |
|---|---|
| `data/orge/orge/materials/air.json` | add `min_flow_mass 0.001`, `max_mass 1000` (keep molar 0.029, state air) |
| `data/orge/orge/materials/steam.json` | (optional Phase B) widen min<default<max so steam compresses |
| `material/MaterialCodec.java` | extend gas crash-guard to `state=air`; ensure `state=air` ⇒ advection material |
| `material/Material.java` / LUT export | expose `molarMass`; mark air as a fluid-participating, compressible gas |
| JNI `step()` + native bridge | pass a new per-material `molar[]` array |
| `ORGE-ENGINE/orge_kernel.hpp` + `sim_engine.hpp` | `MatLUT.molar`; remove `lut.air` discard semantics; vacuum sentinel = empty; gas volume-fill + compressible min/max; density swap incl. air; liquid-displaces-gas (swap, no consume); (Phase B) pressure `mRT/M`. **Bit-identical.** |
| `ORGE-ENGINE/tests/advection_parity_test.cpp` | vacuum-fill, air-compress, liquid-displaces-air-not-consume, molar-ordering, break→vacuum cases |
| `scheduler/StepValidator.java` | air = tracked species; drop air-credit / air-untracked exemption |
| `scheduler/SeamCoStep.java` / `MinecraftThermalWorld` | co-step the gas column above an active surface |
| block-edit / reseed path (both loaders) | broken block → **vacuum** cell (mass 0), record signature from engine output |
| `core/.../resources/natives/linux-x64/liborge.so` | rebuilt + bundled at integration; bump gitlink |

---

## 12. Rulings (DECIDED 2026-05-31)

1. **Steam stays fixed-mass in Phase A** — `min=default=max=0.6` unchanged; the compressible-gas rule is
   proven on **air** first. Steam compressibility folds in with Phase B.
2. **Phase B (ideal-gas pressure `mRT/M`, convection) is BANKED.** This effort ships **Phase A only** —
   the mass-from-nothing fix: vacuum/finite-gas/compressible-air/density-sort/break→vacuum, with molar
   mass *plumbed* (data) but not yet *driving motion*.
3. **Air `max_mass = 1000 kg`** is the starting compression cap; tune the cap + swap hysteresis in the
   C++ reference sim + SDL viewer, not in the live game.
4. **Place-into-saturated-sealed-gas** (§5): accept the **one-cycle batch-hold** (no relief valve) — a
   rare, self-correcting edge; the place still succeeds, the batch just pauses that cycle.

## 13. Verification

- ENGINE parity + advection suites green incl. the new vacuum/compress/displace/molar cases
  (`kernel == sim_engine`).
- MAIN `:core:test` green incl. air-as-species §9 + co-step + native E2E
  (`1000 → N cells → 1000.0`; `break → vacuum → Σair conserved`); both loaders build.
- In-game (user): pour water across cells → no mass growth; break blocks → vacuum then air refills, no
  air-from-nothing; steam rises through air; sealed pocket resists; no lag, dormancy intact.
