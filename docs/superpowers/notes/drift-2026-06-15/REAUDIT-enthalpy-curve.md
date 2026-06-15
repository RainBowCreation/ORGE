# Drift re-audit — `EnthalpyCurve.java` vs v4 §8.1 / DESIGN-LAW #6

Date: 2026-06-15. Auditor scope: the NEW
`core/src/main/java/net/rainbowcreation/orge/material/EnthalpyCurve.java`.
Source of truth = SPEC (`specs/2026-06-10-engine-b-unified-spec-v4.md` §8.1 + §1.2) and
`docs/superpowers/DESIGN-LAW.md` #6/#7. Code comments + tests DISCARDED as proof. Engine
`ORGE-ENGINE/core/sim_engine.hpp` `namespace orge_enthalpy` (lines 99–220) read ONLY as a
cross-check reference for curve agreement.

READ-ONLY audit. No code changed.

## Verdict
**No CODE-BUGs found.** The Java curve is a faithful, parity-grade mirror of the engine's
authoritative chain-anchored curve and is law-/spec-compliant on every point checked. One Java-only
divergence exists (a depth cap absent in C++) but it only changes behavior on *invalid cyclic* LUT
data, where it fails safe — not drift. One caller-side concern (lossy re-encode in a dormant path)
is OUTSIDE this file and already acknowledged by the spec/comment.

Counts: BLOCKER 0 · MAJOR 0 · MINOR 0 (code bugs). Notes: 2 (1 benign Java/C++ divergence,
1 out-of-scope caller observation).

---

## Verify-list results

### 1. Inverse T(E/m): piecewise-linear slope 1/cp + latent plateau of width L — COMPLIANT
Spec §8.1: "h piecewise linear (slope cp) whose inverse T(E/m) has a plateau of width L at each
phase threshold." Law #6 same.

`tOfEta` (EnthalpyCurve.java:98–136):
- base affine inverse `double T = a.tAnchor() + (eta - a.hAnchor()) / cp;` (line 105) — slope 1/cp. ✔
- maxTemp plateau (heating, e.g. water boil): band `[h*, h*+L]` pinned at `T*`, slope resumes above:
  ```
  if (eta >= hStar && eta <= hStar + L)      T = tStar;                         // 116-117
  else if (eta > hStar + L)                  T = tStar + (eta - hStar - L)/cp;  // 118-119
  ```
- minTemp plateau (cooling, e.g. freeze/condense): band `[h*-L, h*]` pinned at `T*`, slope resumes
  below (lines 129–133).

This is the F1 fix proper — there is NO single-slope `T = (E/m)/cp` that ignores latent. The
single-slope form only emerges for a chain-ROOT material with no plateau (anchor (0,0)), which is
correct (a root has no phase pairing). The plateau lives ONLY in the inverse; the forward `hOf`
returns the plateau bottom (line 91) — matching the spec's single-valued forward map. ✔

### 2. Chain-anchoring — continuous T, identity-on-E relabel, terminating walk — COMPLIANT
Spec §8.1 / law #6: `h_target(T*) ≡ h_donor(T*) + L`; relabel = identity on E (ΔE≡0); T continuous;
ice→water→steam chained; cold chain ≤3 hops.

`curveAnchor(Material, lookup, depth)` (EnthalpyCurve.java:66–83):
- ROOT when no resolvable `minTarget` ⇒ `(0,0)` ⇒ `h(T)=cp·T` (line 73). ✔
- recurse on the COLDER phase (`minTarget`), anchor at this material's own `minTemp`:
  `hColderAtStar = ca.hAnchor() + colder.heatCapacity()*(tStar - ca.tAnchor());`
  `return new CurveAnchor(tStar, hColderAtStar + m.latentHeatMin());` (lines 80–82)
  i.e. `h_anchor = h_donor(T*) + L` exactly as normative. ✔
- ΔE≡0 bit-exactness: at the threshold `hOf` evaluates `h_anchor + cp·(T* − T_anchor)` with
  `T*==T_anchor` ⇒ `cp·0.0 == 0.0` ⇒ returns `h_anchor`, which is by construction
  `h_donor(T*)+L` — identical double. Same construction as the engine (sim_engine.hpp:155–157). ✔
- Termination: walk strictly descends to a colder phase; valid chain ≤3 hops. Java adds a
  depth>8 guard returning the `CYCLE` sentinel, which collapses the WHOLE walk to ROOT
  `(0,0)` (lines 50, 56, 67–68, 77–78). For all valid (acyclic) data the cap never trips ⇒
  identical to the engine. (See Note A for the engine's lack of this guard.) ✔

Cross-check: this is a line-for-line transcription of engine `curve_anchor`
(sim_engine.hpp:161–172) plus the divergence in Note A.

### 3. Values come from the LUT, not hardcoded — COMPLIANT
Every parameter is read from the `Material` record at call time:
`m.heatCapacity()`, `m.latentHeatMin()`, `m.latentHeatMax()`, `m.minTemp()`, `m.maxTemp()`,
`m.minTarget()`, `m.maxTarget()`. No numeric literal for cp, L, or thresholds appears anywhere in
the file (only structural constants: depth cap 8, NaN sentinel, 0.0 root anchor). The `Material`
record (`material/Material.java`) carries exactly the §1.2 schema fields, and the §1.2 LUT row
values (water 4186 / L=3.34e5@273 / L=2.256e6@373; lava 1150 / 1275→stone L=4.0e5; ice 2108;
steam 2080 / 373→water L=2.256e6) flow through unchanged. ✔

### 4. Agreement with the engine C++ curve — COMPLIANT (parity-grade mirror)
Side-by-side, Java `EnthalpyCurve` == engine `orge_enthalpy` (sim_engine.hpp 131–220):
| concern | engine | Java | agree |
|---|---|---|---|
| CurveAnchor record | `{T_anchor,h_anchor}` 160 | `CurveAnchor(tAnchor,hAnchor)` 42 | ✔ |
| ROOT sentinel | `minTarget==MAT_NO_TARGET ‖ ≥size` →(0,0) 163-164 | `coldId==null ‖ lookup→null` →(0,0) 70-73 | ✔ (semantically equal) |
| anchor formula | h_colder + L 169-171 | identical 80-82 | ✔ |
| forward h_of | `h_anchor+cp·(T−T_anchor)` 177 | identical 91 | ✔ |
| inverse base | `T_anchor+(eta−h_anchor)/cp` 186 | identical 105 | ✔ |
| max plateau | band `[h*,h*+L]` pin, slope above 191-197 | identical 112-121 | ✔ |
| min plateau | band `[h*-L,h*]` pin, slope below 201-207 | identical 125-134 | ✔ |
| cp<=0 / mass<=0 fallbacks | 183 / 217 | 100 / 151 | ✔ |
| double math, float at return | yes 208/214/218 | yes 135/143/154 | ✔ |
The MC `null` colder-phase ⇒ ROOT mirrors the engine's `MAT_NO_TARGET ‖ ix≥size` ⇒ ROOT, so an
unresolvable target degrades identically on both sides. For valid data, `deriveT(E,m,mat)` on the
Java side returns the same Kelvin (within a float ulp) as the engine's `derive_T` ⇒ DECODE relabels
and Java display will NOT disagree. ✔

### 5. Derive-only / no lossy E write-back from EnthalpyCurve — COMPLIANT for this file
`EnthalpyCurve` is `final`, private ctor, pure static methods, no state/I-O (matches the law-#7
"derive intensive, never store"). It exposes `hOf`/`cellE` (forward ENCODE) and
`tOfEta`/`deriveT` (inverse DECODE). It never writes anything back itself.

Caller audit (all 7 call sites):
- DISPLAY paths use `deriveT` to produce a Kelvin for observability ONLY, never re-storing E:
  - `command/ServerStoreReadSource.java:55` — derives display T, then `StepValidator.clampDerivedKelvin`;
    comment + code confirm "stored E is never touched", clamp is display-only. ✔
  - `scheduler/MinecraftThermalWorld.java:747` — derives the engine's *diagnostic* `tIn`
    (`temps[i]`), then `clampDeriveBoundary`; the authoritative `enthalpy[i] = sd.enthalpyAt(i)`
    is handed to the engine RAW/loss-free (line 739). The clamped T does NOT feed stored E. ✔
- ENCODE paths use `cellE`/`hOf` only to set an initial/seed E (E=m·h(T_seed)) where stored E is
  genuinely unset, never to re-encode an authoritative E:
  - `scheduler/ColumnAssembler.java:172-175` — `inE != 0 ? inE : cellE(...)`; encodes ONCE only
    when stored E == 0 (seed/ambient). ✔
  - `command/ServerStoreWriteSink.java:66`, `phase/MinecraftPhaseChanger.java:152` — encode E for a
    freshly written/placed cell at a known T. ✔
The [0,6000] clamps (`StepValidator.clampDerivedKelvin` / `clampDeriveBoundary`) are applied to the
DERIVED display/diagnostic T only and never round-trip back into stored E. ✔

---

## Notes (not code bugs)

### Note A — Java adds a depth-cap cycle guard the engine lacks (benign divergence, NOT drift)
Engine `curve_anchor` (sim_engine.hpp:161-172) has NO explicit recursion bound — it relies on the
LUT being acyclic / strictly-descending. Java `curveAnchor` adds `if (depth > 8) return CYCLE;`
(line 67) and collapses the whole material to ROOT on a cycle (line 56). For ALL valid data
(acyclic, ≤3 hops, per §8.1) the cap NEVER trips ⇒ Java and engine are bit-identical. They diverge
ONLY on a *cyclic / self-referential* datapack LUT (e.g. A→B→A or self-target): the engine would
infinite-recurse (StackOverflow / crash), Java fails safe to single-slope. This is defensive
hardening on invalid input, not a spec deviation; the spec caps the chain at ≤3, so neither path is
exercised by conformant data. Severity: informational. CODE-BUG? No. If anything, the engine is the
less-robust side, but on valid data both are correct.

### Note B — Lossy E re-encode in the DORMANT per-section write-back (CALLER, out of scope, acknowledged)
`scheduler/MinecraftThermalWorld.writeBack` (line 357) does `dst[i] = cellE(mi, cellM, tOut[i])`,
re-encoding stored E from the engine's DERIVED `tOut`. Because the curve is plateau-pinned, a
mid-latent cell (e.g. boiling water with η ∈ [h*, h*+L]) decodes to T*=373 and re-encodes to the
plateau BOTTOM h* — silently discarding the latent offset (up to L·m joules). This is the classic
lossy-reconstruction the law forbids. HOWEVER:
- it is in the CALLER, not `EnthalpyCurve.java` (this audit's module);
- the file's own comment (and the spec's live-path design) states this DORMANT per-section path's
  `StepResult` "has no extensive-E channel" and that "the live column path stores eOut directly";
  i.e. the authoritative tick path (column assembler / `enthalpy[i]=sd.enthalpyAt(i)`) carries E
  loss-free and does NOT go through this re-encode.
Flagging for completeness so a follow-on can confirm the dormant path is either unused for
mid-latent cells or routed to carry extensive E like the live path. Recommend the Module-F /
persistence audit own this — it is a StepResult-channel gap, not a curve bug.

---

## Confirmed-compliant summary
1. Latent plateaus present in the inverse (width L at each threshold); no single-slope F1 regression. ✔
2. Chain-anchored (`h_anchor = h_donor(T*)+L`), ΔE≡0 by exact-double construction, T continuous,
   terminating bounded walk (+ extra cycle guard). ✔
3. All cp / L / threshold / target values sourced from the LUT (`Material`), none hardcoded. ✔
4. Bit-for-bit structural parity with the engine's authoritative `orge_enthalpy` curve ⇒ Java
   display/relabel agrees with the engine DECODE. ✔
5. Pure, stateless, final; derive paths are display/diagnostic-only with display-only [0,6000]
   clamps that never feed stored E back. ✔
