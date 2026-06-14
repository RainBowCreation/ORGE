# ⚠ PROPOSED AMENDMENTS to spec v4 (§5.3 / §6.1 / §1.3 / §2.1) — REQUIRES USER RATIFICATION (Way-2)

**This file does NOT change the law or the spec. Only the user edits `DESIGN-LAW.md` and the v4 spec.
This is a PROPOSAL for the user to ratify.**

These items record the wording the **T4b-Way-2 implicit pressure→momentum coupling** work (subtasks 1–8,
landed at engine `rebuild` `8481d56`) makes true of the running engine, so the v4 spec can be matched to the
code with a deliberate user edit. They follow the prior proposal's format
(`DESIGN-LAW-AMENDMENTS-PROPOSED-2026-06-11.md`): per item **Current** (verbatim) / **Defect** / **Proposed**
(exact replacement text the user can paste) / **IMPACT** (consumers / tasks / guarded §11 invariants / code
delta) / **CAUTION** (chain analysis).

**What Way-2 shipped (the source of truth for this draft).** A per-cell **pressure stiffness**
`s = ∂p/∂m` [Pa/kg] — for a gas `s = R·T/(M·V)` (= ∂p_eos/∂m); for an incompressible `s ≡ 0` because
`p_eos ≡ 0` (§2.2) — plus an **implicit / under-relaxed** treatment of the EOS pressure imbalance in the
drive map. The under-relaxation factor is `θ = 1/(1 + s·c·dt)` with coupling group `c = A²·dt/V`
(so `s·c·dt` is dimensionless ⇒ `θ ∈ (0,1]`), applied so that θ damps **only** the imbalance from
hydrostatic support: with the support impulse `i_support = m·g·dt·ŷ`, the applied impulse is
`i_applied = i_support + θ·(i − i_support)`. Advected momentum (`ṁ·u_donor`) and gravity (folded into `u_g`)
pass **UNSCALED** — θ never touches the journey, only the stiff-EOS push. Results: a resting gas cell's
`vel_damp` heat collapsed **13,789 → 0 J/tick** (the smoke-scene churn ~6e6 → ~0 J/tick); `maxAirU`
**1.333 (CFL-railed) → 0**; surface air `‖u‖ < 0.01·dx/dt`; liquids **bit-identical** (`s ≡ 0 ⇒ θ = 1 ⇒`
the formula is the identity on them, their hard wall stays `receiver-room = 0`); and the θ-on fixed point is
**identical** to the explicit form — the sealed INV-GAS pocket settles at the closed-form `1 + ρgh/P0` to
within 0.05%. The dead `gas_ladder_room`/`LADDER_*`/`gasSpent` symbols were deleted (their `s = R·T/(M·V)`
physics was absorbed into the stiffness term). **Subtask 5 (cross-gas absolute-P) ESCALATED (E2) and did NOT
land — see W2-3.**

---

## Item W2-1 — §5.3 / §6.1: flux is QUASI-STATIC via an implicit, under-relaxed pressure→momentum coupling

**Current — §5.3** (the flux-rate line; the only rate clause):
> "Flux rate: `λ = μ/(ρ·dx²)` [1/s], damping `1/(1+dt·λ)` (kept)."

**Current — §6.1** (the normative drive in R2 / the pass loop; the drive velocity is taken from the
post-force explicit fold-in, not under-relaxed):
> "**R2 (commit):** a face fluxes **iff neither endpoint is swapMutual**; flux = `f_k(donor)·σ_donor·
> ρ'_receiver` … Mutual swaps permute **snapshot payloads** (§7.1)." (The drive velocity feeding the per-face
> outflows `f_k` is the explicit post-force `u = u_g + i/m`, with no stiffness damping.)

**Defect:** the explicit, velocity-driven fold-in integrated the **full** stiff-EOS pressure impulse forward
each tick. With a gas EOS stiffness `s = ∂p_eos/∂m ≈ 82 kPa/kg` and a large `dt`, the impulse `i/m` flies
**past** the hydrostatic force-balance, the cell over-shoots, the relaxed `∇P` reverses it next tick, and the
pair enters a **CFL limit cycle** — a resting gas cell that should be motionless instead oscillates at the CFL
velocity ceiling (`maxAirU` railed at 1.333). DECODE's `vel_damp` converts that fake kinetic energy to heat
every tick: **~4944 J/tick** of fabricated heat in a cell that is at mechanical equilibrium (the
gravity→overshoot→damp→heat pump). Nothing in §5.3/§6.1 makes the **stiff pressure** quasi-static; the
existing `1/(1+dt·λ)` viscous damping is a *viscosity* rate, not a *pressure-stiffness* rate, and does not
tame this.

**Proposed — §5.3** (append after the flux-rate/swap-cadence block, a new "Stiff-pressure quasi-static
coupling" clause):
```
**Stiff-pressure quasi-static coupling (implicit, under-relaxed).** A cell's own EOS pressure resists its
own mass change with stiffness s = ∂p/∂m [Pa/kg]: gas → s = R·T/(M·V) (= ∂p_eos/∂m); incompressible
(max == default) → s ≡ 0 (p_eos ≡ 0, §2.2). The stiff-EOS contribution to the drive is treated IMPLICITLY,
under-relaxed by the dimensionless factor

  θ = 1 / (1 + s·c·dt)        c = A²·dt/V        (s·c·dt dimensionless ⇒ θ ∈ (0,1])

θ scales ONLY the imbalance away from hydrostatic support. With i_support = m·g·dt·ŷ the cell's own
half-cell hydrostatic support impulse, the per-tick impulse applied to momentum is

  i_applied = i_support + θ·(i − i_support)

so a gas in force-balance (i ≈ i_support) is undamped, and any residual EOS-pressure overshoot is damped
toward zero each tick rather than integrated forward at full size. For an incompressible cell s ≡ 0 ⇒ θ = 1:
the −∇P·V·dt constraint drive passes through unscaled (liquids flow exactly as before; their hard wall is
receiver-room = 0, NOT θ). The fixed point is identical to the explicit form — θ moves only the approach,
never the equilibrium. This makes the flux QUASI-STATIC in the stiff-pressure sense: pressure decides the
destination (the balance), momentum decides the journey (the motion); θ only stops the stiff pressure from
shoving momentum past the destination.
```

**Proposed — §6.1** (amend the drive feeding R1/R2 — add one sentence to the R2 description):
```
The per-face outflows f_k are driven by the §5.3 θ-under-relaxed velocity for the STIFF-EOS pressure
imbalance only; gravity (folded into u_g in ENCODE) and advected momentum (ṁ·u_donor) pass UNSCALED — θ
tames the stiff pressure, never the journey. (No state branch: θ comes from the per-cell stiffness s, which
is material-derived data via the χ/EOS path — s ≡ 0 for incompressibles by the §2.2 rule, exactly like the
viscous dragScale is data.)
```

**IMPACT:** consumers = the §6.1 DriveCtx pre-pass / R1 flux-intent (the drive-velocity computation,
`core/engine_b.hpp` ~lines 800–1017) and the §5.3 rate clause. Affected task = **T4b-Way-2** (landed; this
matches the spec to it). **Deletes** the now-dead `gas_ladder_room` / `LADDER_BETA` /
`LADDER_REST_DEADBAND` / `gasSpent` symbols (their `s = R·T/(M·V)` physics is absorbed into the stiffness
term — see W2-2; the §1.3 retired-symbols list grows). Guards §11 **INV-AL** (resting gas
`‖u‖ < 0.01·dx/dt`, no monotonic static heating — now met at full strength), **INV-GAS** (sealed pocket
1.20 ± 0.05×, P ≈ overburden, settled == closed-form), **INV-P1** (75,006 Pa bottom / 80,000 Pa floor-face —
unchanged, liquids bit-identical), **INV-P2** ([1000|500] → [750|750], 1500.0000 kg exact — unchanged), and
**INV-UNIVERSAL** (θ from per-cell stiffness data, no `switch(state)` in flux). **Code delta:** already
landed at engine `8481d56` (subtasks 1–4, 6–8); this amendment is text-matched-to-running-code.
**CAUTION:** chain to **§3.2** — the implicit coupling fixes *correctness* (rest-cold), not deep-column
*convergence speed*. If deep oceans/atmospheres miss the perf gate within the `N_relax` sweep budget, the
sanctioned remedy is the §3.2 **banked per-chunk geometric multigrid** (a uniform O(H) accelerator), **never
a gas band-aid** — Way-2's E1 escalation is exactly this. Do not reintroduce a per-state flux path to chase
convergence.

---

## Item W2-2 — §1.3 frozen manifest: discharge the LADDER_* exemption; enroll `c` as derived material data

**Current — §1.3** (the retired-symbols line and the T4-transient exemption):
> "Retired symbols (must not reappear): `own_weight_head, p_surf, swap_kv, swap_threshold, head_relax,
> p_ac_scale, (1−χ) relaxation factor, T_curr/T_next, persisted vx/vy/vz`.
> `LADDER_BETA`, `LADDER_REST_DEADBAND`, `GAS_CHI_MIN` are **T4-transient gas-ladder knobs, exempt from
> INV-3** until T4's flux-intent rebuild deletes them — the manifest CI-diff does not fail on them meanwhile
> `[LAW-AMEND-v42-A5 / owner T4]`."

**Defect:** the flux-intent rebuild (Way-2) has now deleted `gas_ladder_room`, `LADDER_BETA`,
`LADDER_REST_DEADBAND`, and `gasSpent` (`grep -rn` over `core/` returns zero — subtask 6). The
**T4-transient exemption is therefore discharged**: those `LADDER_*` symbols are no longer "exempt-but-
present", they are simply gone and belong on the **retired-symbols** line (a re-appearance must fail INV-3
like any other retired symbol). `GAS_CHI_MIN` survives only OUTSIDE flux (force EOS-anchor selection and
radiation classification), not as a flux gate. Separately, Way-2 introduces the stiffness coupling group
`c = A²·dt/V`; the manifest must record that it is **derived material/geometry data, NOT a free knob** — it
is computed from `A`, `V`, `dt` (and `s` from `χ`/`molarMass`/`T`), never tuned — so a reviewer does not
mistake it for an INV-3 manifest knob.

**Proposed — §1.3** (replace the retired-symbols + exemption block):
```
Retired symbols (must not reappear): `own_weight_head, p_surf, swap_kv, swap_threshold, head_relax,
p_ac_scale, (1−χ) relaxation factor, T_curr/T_next, persisted vx/vy/vz, gas_ladder_room, LADDER_BETA,
LADDER_REST_DEADBAND, gasSpent`. The T4-transient gas-ladder exemption is **DISCHARGED** — the Way-2
implicit pressure→momentum coupling deleted these symbols (their s = R·T/(M·V) physics is absorbed into the
per-cell stiffness term, §5.3); a re-appearance now fails INV-3 like any other retired symbol.
`GAS_CHI_MIN` survives ONLY outside the flux/room path (force EOS-anchor selection, radiation
classification); it is not a flux knob and not enrolled. The Way-2 stiffness coupling group `c = A²·dt/V`
is **DERIVED data, NOT a free knob** (computed from A/V/dt; the stiffness s = ∂p/∂m is computed from
χ/molarMass/T) — it carries no manifest range and is exempt from INV-3 as a derived quantity, like the
viscous dragScale = 1/(1+dt·λ).
```

**IMPACT:** consumers = the §1.3 CI-diff (INV-3) and the audit's retired-symbol grep. Affected task =
**T4b-Way-2** (subtask 6 deleted the symbols; subtask 8 cleared the stale `LADDER_*`-as-flux comments).
Guards §11 **INV-3** (manifest grep-zero on the four retired symbols) and **INV-UNIVERSAL** (no surviving
flux-path state symbol). **Code delta:** already landed; this is the manifest-text match.
**CAUTION:** keep `c` and `s` on the *derived* side of the manifest, never enrolled as knobs — enrolling
them would invite "tuning" the coupling, which is the velocity-driven instability Way-2 removed. The only
INV-3-relevant change is that `LADDER_*`/`gasSpent` move from *exempt-present* to *retired-absent*.

---

## Item W2-3 — §2.1 cross-gas absolute-P: NOT landed (ESCALATE-E2); records the topological block

> **NOT IMPLEMENTED.** Way-2 subtask 5 attempted to re-enable cross-gas absolute-P and **E2-escalated to
> STOP**. This item does NOT mark it implemented; it records the real prerequisite so the spec's pending
> qualifier is precise.

**Current — §2.1:**
> "**Cross-gas faces compare ABSOLUTE pressure** (`p_eos + P0` each side) so per-gas gauge offsets cancel
> `[LAW-AMEND-v42-A2 / owner T4]` — to be implemented at **hetero-gas (different-species) faces on the EOS
> anchor** in T4 (same-gas faces stay gauge-cancelled); INV-AL needs an air|steam absolute-P variant once it
> lands."

**Defect (refines the earlier framing):** the prior framing assumed cross-gas absolute-P was merely *gated
on the ST7 velocity-driven gas instability* — i.e. that taming the stiff gas (Way-2) would unblock it. Way-2
did tame the stiffness, and the attempt still escalated, exposing a deeper **topological / consistency-of-
reference** block: an antisymmetric hetero-gas face requires each side to read the **same** absolute face
pressure (weight-½ `P0_i` from each endpoint). But a gas cell's **boundary** faces (vacuum `= 0`, solid
`= ownf`) are **physics-dictated gauge** — they cannot also carry `P0_i` without railing the resting
atmosphere into vacuum at the full `p_abs` (~99 kPa) scale. So `P0_i` lands on a **non-cancelling subset** of
the cell's six faces ⇒ an **unavoidable P0-scale self-force** (~99 kN) when one face is absolute and the
others are gauge. **θ does NOT cure this** — it is a *consistency-of-reference* problem (which gauge each
face speaks), not a *stiffness* problem (how hard the pressure pushes). A correct absolute-P force needs the
cell to compute **all six faces in one self-consistent reference**, which is the deeper **ST7 quasi-static-
gas-mass rebuild**, not a face-local tweak.

**Proposed — §2.1** (replace the cross-gas sentence with a precise pending record):
```
**Cross-gas faces SHOULD compare ABSOLUTE pressure** (`p_eos + P0` each side) so per-gas gauge offsets
cancel `[LAW-AMEND-v42-A2]` — **NOT YET IMPLEMENTED (ESCALATED, owner ST7).** A Way-2 attempt (engine
8481d56 era) found the blocker is NOT the velocity-driven gas instability (Way-2 already tamed the gas
stiffness) but a CONSISTENCY-OF-REFERENCE / frame-mix problem: a hetero-gas face needs weight-½ P0 from each
side, but a gas cell's boundary faces (vacuum = 0, solid = ownf) are physics-dictated gauge, so P0 lands on
a non-cancelling face subset ⇒ an unavoidable P0-scale (~99 kN) self-force; θ cannot fix a reference
problem. A consistent absolute-P force requires all six faces in ONE reference — the deeper ST7 quasi-
static-gas-mass rebuild, the user's call. Until then, hetero-gas faces compare GAUGE p_eos and a resting
air|steam interface carries a documented ~4.3 kPa false equilibrium. INV-AL's air|steam absolute-P variant
remains PARTIALLY OPEN: the resting case conserves trivially (both gauges are 0 at each gas's own rest
state), but the non-resting discriminating case (true equilibrium at unequal gauge) needs this rebuild.
```

**IMPACT:** consumers = the §3.1 gas-neighbor anchor and the §4 GAS-j force branch. Affected task = a future
**ST7** (quasi-static-gas-mass rebuild) — **not** the landed T4b-Way-2. Guards §11 **INV-AL** (air|steam
variant) — its resting clause still passes (both gauges 0); its non-resting discriminating clause stays
OPEN. **Code delta:** none landed (subtask 5 reverted); the work is owned by ST7.
**CAUTION:** do not re-frame this as "gated on velocity-driven instability" again — Way-2 proved that frame
wrong. The accurate prerequisite is single-reference consistency across all six faces (ST7). Any future
attempt MUST add the air|steam non-resting discriminator (two gases at *true* mechanical equilibrium with
unequal gauges read NO spurious gradient) before claiming the variant closed.

---

## LAW IMPACT: NONE — the frozen `DESIGN-LAW.md` needs NO change

Way-2 is a **numerical METHOD** change — implicit/under-relaxed vs explicit integration of the *same* law #2
6-face force into the *same* law #7 momentum. It introduces no new physics, no new field, and no new rule.
The drift test (DESIGN-LAW.md bottom) is walked one by one:

- **(a) "a second pressure number" — NO.** There is still exactly ONE pressure number per cell. The
  stiffness `s = ∂p/∂m` is the *derivative* of the cell's existing `p_eos` (law #9's gas EOS), not a second
  pressure. `θ` scales an **impulse** (`i_applied = i_support + θ·(i − i_support)` — units kg·m/s), never a
  pressure. No `P_a/P_b` split, no `p_eos` summed with `P` as a rival pressure (the §2.1 "for the same cell"
  guard is untouched).

- **(b) "a force rule that differs by direction" — NO.** `θ` is a **scalar** applied per-axis identically;
  the same `θ = 1/(1+s·c·dt)` multiplies the x, y, z components of the imbalance the same way. The
  y-vs-lateral asymmetry that appears (`i_support = m·g·dt·ŷ`) is **gravity's own physical direction** — the
  hydrostatic support a cell genuinely presses against — not a rule that treats up/down/sideways by
  different *force law*. Law #3 (same rule all 6 directions) holds: a deep side-hole still gushes because the
  deep cell's big `P` faces a small `P` across that face, unchanged.

- **(c) "a stored temperature treated as source-of-truth / a separate conduction pass" — NO.** Way-2 touches
  only the mechanical drive. No intensive field is stored (`s`, `c`, `θ` are derived every tick from
  `mass`/`E`/material data, never persisted); `T` is still `h⁻¹(E/m)`, derived; there is no separate
  conduction pass — thermal still rides the same RESOLVE pipeline (law #6), untouched by this work.

- **P-0 (one universal cell law, never branch by state) — PRESERVED.** The stiffness `s` is **material data**
  computed from the same χ/EOS path (`s = R·T/(M·V)` for a gas; `s ≡ 0` for an incompressible because
  `p_eos ≡ 0` by the §2.2 *material* rule), exactly as the viscous `dragScale = 1/(1+dt·λ)` is data. The
  flux/room path contains **no** `switch(state)` / `if (isGas)…else if (isLiquid)…` — INV-UNIVERSAL's grep
  guard stays green; the only surviving `is_gas`/`GAS_CHI_MIN` uses are outside flux (force EOS-anchor
  selection, radiation classification), which are the law's sanctioned material-class reads. Liquids being a
  NO-OP is a *consequence of their material data* (`s ≡ 0 ⇒ θ = 1`), not a state branch.

**Conclusion: DESIGN-LAW.md is unchanged.** Only the v4 spec wording (§5.3, §6.1, §1.3, §2.1) is proposed for
the user's edit, to match the spec to the running engine. This file makes no edit to `DESIGN-LAW.md` or the
v4 spec; the user ratifies by editing them.

---

## Ratification checklist (Way-2)

| # | Item | Kind | Code delta | Landed? |
|---|------|------|-----------|---------|
| W2-1 | §5.3/§6.1 implicit θ stiff-pressure coupling (`s=∂p/∂m`) | spec | none (text-match) | ✅ engine 8481d56 |
| W2-2 | §1.3 discharge LADDER_* exemption → retired; `c` = derived data | spec | none (text-match) | ✅ engine 8481d56 |
| W2-3 | §2.1 cross-gas absolute-P — NOT landed (ESCALATE-E2, owner ST7) | spec | none (reverted) | ❌ escalated |
| — | DESIGN-LAW.md | — | **NONE** | n/a (law unchanged) |

After ratification the user edits the v4 spec (§5.3/§6.1/§1.3/§2.1); this file and the pre-amendment text
live in git history.
