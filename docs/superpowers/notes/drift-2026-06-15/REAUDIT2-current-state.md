# Engine-B — DRIFT RE-AUDIT #2 (current state, after F1/F2/C-1 landed)

**Date:** 2026-06-15 (re-run #2) · **read-only** · spec=truth, code/tests/commit-msgs presumed buggy, **all comments discarded**.
**HEADs audited:** parent `54d47f0` · engine `726bae5`.
**Authoritative truth:** `DESIGN-LAW.md` (frozen v4.2) ▸ `specs/2026-06-10-engine-b-unified-spec-v4.md`.
**Prior set:** `00-MASTER-drift-report.md`, `REAUDIT-00-summary.md`.

---

## Status table — the four discussed findings

| finding | first audit | re-audit #1 | **NOW (this re-audit)** |
|---|---|---|---|
| **F1** enthalpy E not persisted (rebuilt m·cp·T) | MAJOR | ✅ RESOLVED (live path) | ✅ **RESOLVED — confirmed clean** (live column path) |
| **F2** momentum-as-velocity | MAJOR | ⚠️ PARTIAL (persisted SoT fixed, live ABI still v) | ✅ **RESOLVED — confirmed clean** (live ABI + JNI now extensive momentum) |
| **C-1** liquid boundary half-cell ghost | MAJOR | 🔴 OPEN (C++ untouched) | ✅ **FIXED — confirmed clean** (`liqf` ghost on all liquid boundary faces) |
| **dormant-E** writeback re-encodes E from T | (new) | 🆕 MAJOR | ⬇️ **DOWNGRADE → MINOR (dead code, no live caller)** |

**Net:** all three authorized fixes are clean at HEAD with no regression and no new drift introduced. The dormant-E finding is real code but **unreachable in the live scheduler** (downgraded). **0 new BLOCKERs, 0 new MAJORs.** The headline live drift is the gas-rest railing — a **pre-existing, labeled** spec-vs-code divergence (NOT introduced by these fixes).

---

## 1. The three fixes — confirmed clean in CURRENT code

### ✅ F1 — enthalpy E persisted end-to-end, no m·cp·T reconstruction
- `NativeEngine.java:168` feeds `eIn = f.eIn()` (stored absolute E) — the old `mass·cp·T` synthesis is **deleted** (comment at :164 confirms intent; executable code at :168/:178/:202 threads `eOut` back, no discard).
- JNI crosses E absolute both ways: `orge_jni.cpp` loads `C->E[i]=eIn[i]` and writes `eOut[i]=C->E[i]` (around :221/:309 per prior trace; the "NEVER reconstruct E from T" path is the executable one).
- Live writeback `MinecraftThermalWorld.writeBackColumn:787-788` stores `result.enthalpy()` (engine eOut) into `data.enthalpyArray()` **unclamped** (the [0,6000] clamp is display-only on derived Kelvin).
- Snapshot `MinecraftThermalWorld:741` reads `enthalpy[i]=sd.enthalpyAt(i)` (raw stored E). **No m·cp·T anywhere on the live path.** CLEAN.

### ✅ F2 — extensive momentum px/py/pz transported live; no v=p/m or cleanM·vOut survives
- **JNI ABI (the re-audit #1 residual hole — now closed):** `orge_jni.cpp:208-210` `C->px[i]=vxIn[base+i]` (loads extensive px directly, no `v·m`); `:317-319` `vxOut[base+i]=C->px[i]` (writes momentum directly, no `v=p/m`). The old `px=v·mass` / `v=p/m` conversions are **gone** (the slots keep the `vx*` name for ABI stability but carry momentum).
- **Java column ABI:** `ColumnTask`/`ColumnResult` carry `momX/momY/momZ` ("EXTENSIVE momentum p [kg·m/s] (law §7)"); `RegionMarshaller:43-45` arraycopies `t.momX()→pxIn`; `NativeEngine:182-185` feeds them through `orgeStepWorld`.
- **Live snapshot:** `MinecraftThermalWorld:735-737` `momX[i]=sd.momXAt(i)` (raw stored extensive momentum — **no `v=p/m`**).
- **Live writeback:** `:803-811` slices `result.momX()` and stores via `StepValidator.cleanMomentum` (finite-or-0 sanitizer) directly into `data.momXArray()` — **NO `cleanM·vOut` rescale.** The re-audit #1 mass-clamp rescale bug is removed; momentum is independent of the §9 mass clamp.
- `SectionData`/codec persist `momX/Y/Z` (no velocity field) — confirmed by re-audit #1, unchanged. CLEAN end-to-end.

### ✅ C-1 — liquid boundary faces carry P_i + ρ_i·g·(r_f−r_i); gas branches unchanged; interior ¼Δρ
- `engine_b.hpp:1025` `const float liqf = P_i - rho_i * ghalf * (float)DY[f];` with `ghalf = 0.5·g·dx` (:1001) = the §4/law-#2 boundary ghost `P_i + ρ_i·g⃗·(r⃗_f−r⃗_i)` (sign: `g⃗=−g·ŷ`, so `−ρ·ghalf·DY`).
- Applied on **every liquid boundary face**: world floor/ceiling `:1028` (`i_gas?ownf:liqf`), absent chunk `:1036`, **vacuum/free-surface `:1043`** (`i_gas?0:liqf` — the new arm vs the original bare `P_i`/`0`), solid `:1053`. The original C-1 defect (bare `P_i` / `0` on liquid boundary faces) is **gone**.
- **Gas branches unchanged:** gas-i boundary faces still use `ownf = a_i − ρ_i·ghalf·DY` (`:1023`, applied at :1028/:1036/:1053/:1104); gas-j neighbor uses `a_j + (mj/V)·ghalf·DY` (:1098); vacuum-against-gas still rails 0 (:1043, `i_gas?0.0f`). Matches ST7.
- **Interior fluid|fluid face `:1116-1117`:** `0.5·(P_i+P_j) + 0.5·(ρ_j−ρ_i)·ghalf·DY` = `½(P_i+P_j) + ¼(ρ_j−ρ_i)·g·dx·DY` (since `ghalf=½g·dx`) — the §4 `¼Δρ` density-difference correction `[LAW-AMEND-v42-A6]`. Correct. CLEAN.

---

## 2. The live GAS-rest drift (the headline) — ACTUAL numbers vs spec

**Measured (this run, engine `726bae5`):**

| scene / test | spec target | MEASURED | over |
|---|---|---|---|
| **INV-AL open/full-chunk atmosphere** (`inv_al_smoke`, `gas_test` INV-AL) | surface ‖u‖ < 0.02·dx/dt | **‖u‖ = 0.00000**, E drift ≈ 0 J/tick | ✅ COMPLIANT |
| **INV-AL walled-cavity** (stone side walls) | surface ‖u‖ < 0.02 | **‖u‖ = 1.33333**, E drift 7.654e6 J / 200 tk, P_bot **253894 Pa** | ❌ ~67× |
| **way2_rest_balance REST** (sealed atmosphere) | ‖u‖ < 0.04, vel_damp < 5 J/tick | **‖u‖ = 1.33333**, vel_damp **15554.5 J/tick**, grand-E drift 15737.7 J/tick (== damp ⇒ no leak) | ❌ ‖u‖ ~33×, heat ~3100× |
| **INV-GAS sealed diving-bell pocket** (`gas_test`) | m = 1.20 ± 0.05× rest; P ≈ overburden; ‖Δm‖/tick → 0 | pocket **m = 1.4403 kg = 1.2003×**; P = **20246 Pa vs overburden 19902 (1.7%)**; late ‖Δm‖/tick = 0 | ✅ pocket COMPLIANT |
| INV-GAS sealed-bell standing heat | (spec: no monotonic heating) | **~2667 J/tick** bounded standing rail | ⚠️ T3-OPEN bounded |

**Characterization (precise):**
- The **gas pocket itself is NOT railing** anymore. The "~2.5×/263 kPa" / "1.31× overshoot" was the **pre-Way2 RED** (the velocity-driven gas-flux limit cycle); the CURRENT Way2 implicit-θ EOS-imbalance under-relaxation has it stabilized at **1.2003× rest, P within 1.7% of overburden, |Δm|/tick = 0**. INV-GAS pocket gates pass.
- The CURRENT live drift is the **resting atmosphere bounded by INTERIOR SOLID side walls**: every cell limit-cycles at `‖u‖ = 1.33333 = dx/dt/(1+vel_damp·dt)` (the §4-velocity cap), and `vel_damp` converts that standing velocity into **~15.5 kJ/tick** of heat forever (the books close — grand-E drift == vel_damp deposit, so it is a *heat pump*, not a leak). The walled-box pressure rails to **P_bot ≈ 254 kPa** (vs the spec's ~12 Pa/cell hydrostatic stratification). The **open-lateral** atmosphere rests cold (‖u‖=0), so the railing is geometry-specific to the **solid|gas vertical-wall boundary force interaction** (§4 boundary face against a stone wall) + the explicit EOS impulse CFL limit-cycle.

**Spec mechanism violated:** §3.1 ("a resting atmosphere relaxes to its own ~12 Pa/cell hydrostatic stratification — gravity balanced by ∇P, not by damping"; the explicit review note: "pinning gas P left a resting atmosphere with unbalanced gravity, vel_damp silently fabricating heat") + §4 `vel_damp` ("With §3.1's stratified-gas rest state this deposits ~0 at rest — no perpetual gravity→damping→heat pump") + INV-AL (§11). The walled-cavity case violates all three.

**Classification:** **MAJOR · spec-acknowledged DEBT (LABELED), not silent drift, NOT introduced by F1/F2/C-1.**
The divergence is explicitly tagged in the executable test output and code:
- `inv_al_smoke` prints `T4b-OPEN(solid-wall): ... OUT OF SCOPE for the INV-AL rest deliverable ... Owner: T4b-Way2 follow-up.`
- `way2_rest_balance` prints `KNOWN-FAIL [T6-OPEN/Way2-ESCALATED]: REST ‖u‖=1.3333 ... Way2 sub-mK EOS-rest fragility.`
- `gas_test` INV-GAS standing heat is `T3-OPEN ... Retirement owner: T4 §6.1 (flux-intent + momentum rework) / κ feedback re-calibration.`

### The A-8 anchor-in-force mechanism (confirmed in executable code)
The spec `[LAW-AMEND-v42-A8]` (§2.1): *a gas cell's relaxed `P` is a force-dead diagnostic; every gas face uses its EOS anchor, not `P`.* Confirmed:
- `engine_b.hpp:996-998`: gas cell's own anchor `a_i = gas_anchor_v42(Mi, T, p_eos4, G)` reads the **per-cell EOS gauge `p_eos4`** (cached in ENCODE at `:370` `e.p_eos4 = p_eos_v4(...)`), NOT `P_i`.
- The force's gas boundary ghost `ownf = a_i − rho_i·ghalf·DY` (`:1023`) and gas-neighbor `a_j` (`:1057,:1098`) both read **the EOS anchor**.
- The relaxed `P` for a gas cell IS computed and persisted (the §3.1 stencil at `:628-664` participates gas cells fully, with the `α_eos·(p_eos,i − P_i)` source at `:664`), but the **force never reads it for a gas face** — every gas face path takes `a_i`/`a_j`/`ownf`/`p_eos4`. So `P` is genuinely force-dead for gas, per A-8.
- This is exactly the §3.1 mechanism that is *supposed* to balance gravity at rest. The walled-cavity railing is the EOS-imbalance explicit-integration CFL limit cycle (the Way2 implicit-θ under-relaxation cures the pocket but not the solid-wall-bounded resting atmosphere) — a labeled T4b/T6 follow-up, not an A-8 violation. **The A-8 wiring itself is correct.**

---

## 3. Prior modules' verdicts at current HEAD

The C++ core math outside C-1 (force `:1020-1130`) and the JNI ABI did not move; the engine diff `a2a51cd→726bae5` is C-1 + ABI grow + JNI momentum + tests. Spot-checks:
- **Module A (State/LUT/EOS/enthalpy §1,§2,§8.1):** unchanged. EOS `p_eos_v4`/`p_abs_hetero_v4`/`gas_anchor_v42` intact (`engine_b.hpp:239-298`); enthalpy chain in `sim_engine.hpp` unchanged. **Verdict holds** (4 minors).
  - A-M1 (`T_curr/T_next` persisted dead vectors): **still present** — `sim_engine.hpp:231-232`, re-derived from E each tick (`:369`, and `engine_b.hpp:2874/:3137-3139` confirm T_curr is a within-tick view derived from persisted E). NOT a transported SoT ⇒ no drift-test-(c) violation; INV-3 struct-diff hygiene only. MINOR, unchanged.
- **Module B (Pressure §3):** stencil `:628-664` unchanged; in-place red–black Gauss–Seidel; gas `α_eos·(p_eos,i−P_i)` source present. **Verdict holds** (1 minor: gas-anchor-floor read in neighbor relaxation).
- **Module D (Gates & 5-pass flux §5,§6):** unchanged; lateral cross-species swap/displace still vertical/gas-only — `owner T4` labeled debt. **Verdict holds.**
- **Module E (Swap & thermal §7,§8.2/3/4):** unchanged. **Fully compliant — verdict holds.**
- **C interior ¼Δρ:** confirmed correct (`:1116-1117`), as before.

**Dormant-writeback latent-loss (re-audit #1's NEW MAJOR) — current status: DOWNGRADED to MINOR (dead code).**
- `MinecraftThermalWorld.writeBack:330` (per-section dormant path) DOES re-encode `E = EnthalpyCurve.cellE(mi, cellM, lookup, tOut[i])` from the engine's **derived T** at `:357` — the latent-loss mechanism is real, and the code comment at `:343-345` admits the StepResult has no E channel.
- **BUT it has no live caller.** `grep '\.writeBack('` (excluding `writeBackColumn`) returns **zero production call sites**; `ThermalWorld.java:45` declares it as a `default {}` no-op; the Scheduler exclusively drives the **column** path (`Scheduler.java:192-224` `snapshotColumns→stepWorld→writeBackColumns→writeBackColumn`), which stores `eOut` directly (F1). So the dormant-E latent loss **cannot fire under the current scheduler**.
- **Reclassify:** MINOR · CODE-BUG-LATENT (dead path that would defeat INV-LAT *if rewired*). Fix-when-touched: give the dormant StepResult an extensive-E channel, or delete the override. Not a live INV-LAT defect today.

---

## 4. §1.3 retired-symbol grep (live path)

Spec §1.3 retired list (`spec:86-87`): `own_weight_head, p_surf, swap_kv, swap_threshold, head_relax, p_ac_scale, (1−χ) relaxation factor, T_curr/T_next, persisted vx/vy/vz`.

| symbol | engine core (`core/*.hpp`) | verdict |
|---|---|---|
| `own_weight_head` | **0** | ✅ retired |
| `p_surf` | **0** | ✅ retired |
| `swap_kv` | **0** | ✅ retired |
| `swap_threshold` | **0** | ✅ retired |
| `head_relax` | **0** | ✅ retired |
| `p_ac_scale` | **0** | ✅ retired |
| `T_curr/T_next` | nonzero (`sim_engine.hpp` legacy core + `engine_b.hpp` within-tick scratch) | ⚠️ present but **derived-from-E within-tick, never stored SoT** → A-M1 struct-hygiene MINOR (INV-3 diff would flag the persisted vector); NOT drift-test-(c) |
| persisted vx/vy/vz | Java now uses `momX/Y/Z`; JNI slot names `vxIn` are vestigial (content = extensive momentum) | ✅ retired as **transported SoT** (F2 closed); cosmetic name only |

Drift-test (a) second-pressure number: **ABSENT** in core (the 6 force/pressure symbols are all 0). Drift-test (b) direction-dependent force: **ABSENT** (one isotropic 6-face rule). Drift-test (c) stored-T SoT: **ABSENT** (T derived from persisted E every tick).

---

## 5. New findings

**None of BLOCKER or MAJOR severity.** The only status change is the **downgrade** of the re-audit #1 dormant-E finding (MAJOR→MINOR, dead code). No new symbols, no new force/pressure drift, no regression from F1/F2/C-1.

Carried MINORs (unchanged): A-M1 (T_curr/T_next dead vectors), A-M2 (swapPartnerKey not in §1.1 seven), A-M3 (`grand_energy` test helper uses m·cp·T), B-MIN (gas-anchor-floor in neighbor relaxation), D-MIN-1/2 (sort tie-breaks), F3 (swapReady in-mem), F4 (void_ix not across ABI), F5 (stale sub-cycle comments).

---

## Bottom line
- **F1 ✅ RESOLVED · F2 ✅ RESOLVED · C-1 ✅ FIXED** — all three clean at HEAD, no regression, no new drift.
- **dormant-E ⬇️ MINOR** — real code, but dead (no live caller); not a live INV-LAT defect.
- **Gas-rest railing** (walled-cavity ‖u‖=1.33 / vel_damp 15.5 kJ/tick / P_bot 254 kPa) is the only live drift vs INV-AL/§3.1/§4 — **MAJOR-severity but LABELED debt** (T4b-Way2 / T6-OPEN / T3-OPEN), pre-existing, not introduced by the fixes. The INV-GAS **pocket** now passes spec (1.2003×, P≈overburden 1.7%, quiescent). The A-8 force-dead-`P` / EOS-anchor-in-force wiring is **correct**.
