export const meta = {
  name: 'drift-audit-t1-t3',
  description: 'Exhaustive law/spec drift audit of engine @ c148f49 (T1–T3 cumulative) + adversarial verification of new findings',
  phases: [
    { title: 'Audit', detail: '10 parallel finders, one per law/spec dimension' },
    { title: 'Synthesize', detail: 'dedup + classify across finders' },
    { title: 'Verify', detail: 'adversarial refute lenses per NEW finding' },
  ],
}

const PRE = `You are a DRIFT AUDITOR for the ORGE Engine-B project. Your job: compare the IMPLEMENTED engine code against the frozen design law and the ratified v4 working spec, and report every mismatch in your assigned dimension. Read-only audit: you may read any file; you must NOT run any test or build (no run_tests.sh in any form — user mandate; reading code is your only instrument).

READ FIRST (in order):
1. /home/claude/ORGE-B/docs/superpowers/DESIGN-LAW.md (THE law, frozen)
2. /home/claude/ORGE-B/docs/superpowers/handoffs/00-MASTER-RULES.md
3. /home/claude/ORGE-B/docs/superpowers/specs/2026-06-10-engine-b-unified-spec-v4.md (the ratified working spec)
4. /home/claude/ORGE-B/docs/superpowers/handoffs/2026-06-11-engine-b-v4-T4-continuation.md (arc state + the KNOWN drift queue)

CODE under audit: /home/claude/ORGE-B/ORGE-ENGINE @ HEAD c148f49 (branch rebuild, clean) — core/engine_b.hpp (~1950 lines, the live pipeline), core/sim_engine.hpp (Chunk/World/Material structs, snapshot, injection), jni/orge_jni.cpp, tests/engine_b_real_lut.hpp. Implementation stages T1 (LUT schema), T2a (single-P relaxation + gas EOS), T2b (force reads only P, A+B deleted), T3 (gas participation) are DONE. Stages T4–T10 are NOT yet implemented; their spec sections are expected to be unimplemented.

CLASSIFICATION RULES — every finding gets exactly one:
- PENDING-IMPL: the spec section's implementing task has not run yet and the code still carries the pre-v4 mechanism awaiting it. Task map: T4=§6 five micro-passes (current RESOLVE is a monolith — expected), T5=§8.2 conduction limiter (current one-sided clamp — expected), T6=§8.3 radiation (absent — expected), T7=§8.1 enthalpy curves/latent + §8.4 (current keep-E relabel without plateaus — expected), T8=§7 swap payload/cadence + §5.3 (current swap gate with swap_kc, swap_visc_rate·sqrt cadence, dPE co-gate — expected), T9=§0 rho_eff convection (absent — expected), T10=JNI LUT columns + E/swapReady crossing (JNI defaults stubbed — expected). PENDING-IMPL is NOT drift; report it only if the pre-v4 mechanism contradicts the law in a way the queue does not track.
- DRIFT-KNOWN: already in the known queue. The queue (from the T4-continuation handoff + reviews): (1) force reads EOS anchors at gas/vacuum faces + §4 half-cell face-value repair (gas_anchor_v42/DriveCtx) contradicting §2.1 "p_eos never read by the force" and §4's half(P+Phi); (2) gas cells' relaxed P force-dead + implicit (not explicit) gas relaxation source; (3) liquid|liquid quarter-delta-rho face correction extending law #2's literal formula; (4) gas_anchor_v42 1%-under-rest floor + dual raw/floored gauge readings (neighbor-gas faces read RAW p_eos with sub-min->0 override); (5) spec §5.2 D<=min full-merge branch HELD (liquid want=0, gas trickles); (6) open-top gas column has no rest state (INV-AL re-authored sealed-top); (7) T3-OPEN held invariants: INV-AL surface-u/no-heating (gas breathing limit cycle) + INV-GAS late-E (standing-rail churn ~2644 J/tick); (8) multi-column leveling slower than pre-T3 (flip target T4/T8); (9) Neumann solid-face ghost pbar=P_i wording not in §4; (10) kappa·divU sourced from PERSISTED velocity not post-ENCODE snapshot; (11) joint stability constraint omega·(1+alpha_eos)<2 missing from §1.3; (12) F1: vacuum-guard zeroes booked E on full-drain cells (~542 J/event class, reduced by T3 but class open); (13) enthalpy_carrier waterfall mass bound widened 1e-3->5e-3; (14) gas flux direction uses post-force w_eff while carried momentum is pre-force u_g; (15) gasSpent allocation order-dependent among competing donors (INV-7 debt); (16) §6.3 eviction decrements recvRoom but never charges gasSpent; (17) canDrain is gasSpent-blind; (18) gas|gas faces carry side-dependent values off-equilibrium; (19) vacuum receiver recvRoom=INF multi-face overfill (T4 INV-RR target); (20) cohesion-settled sub-min 150-tick streak gate looser than §6.4 "next RESOLVE drains"; (21) bug3_accept still seeds lava at 3100 > v4 maxMass 2650 (stale scene premise, T7); (22) vacuum cells skipped as relaxation centers (spec says "every non-solid cell").
- DRIFT-NEW: contradicts the law or the ratified spec text and is in NEITHER the queue above NOR sanctioned by a labeled in-code debt comment pointing at an owner task. THIS IS WHAT WE ARE HUNTING.
- NOTE: compliant but worth recording (e.g., a sanctioned interpretation, an ambiguity both readings satisfy).

For each finding: cite the exact law clause or spec section sentence, the code site (file:line at c148f49), one-or-two-sentence description, severity (high = changes physics/conservation/persisted state; medium = formula/knob/manifest mismatch; low = wording/comment/test-shape), and concrete evidence (the code expression vs the spec expression). Be exhaustive within your dimension; do not report other dimensions' territory unless you found something the dimension split would otherwise drop. Your final message: ONLY the structured findings.

YOUR DIMENSION: `

const DIMS = [
  { key: 'law-1-3', focus: `Law clauses #1 (ONE scalar P, only persisted intensive, iteratively relaxed), #2 (ONE Vector3 force: face-average surface integral -gradP*V*dt + advected momentum m_dot*u_donor; gravity+external ONCE in ENCODE; 1-hop reads only), #3 (same rule all 6 directions), and the mechanical drift test (a)/(b). Audit the force construction (DriveCtx pre-pass, additive channel W drive, displacement driver), the relaxation, gravity application sites (exactly once? check ENCODE u_g AND any other g use that adds momentum), external-impulse path, and hunt for ANY direction-conditional branch (DY[f] used for anything beyond the g-dot-product/hydrostatic offsets), any column sweep, any global sum feeding forces.` },
  { key: 'law-4-5', focus: `Law clauses #4 (one step ENCODE->RESOLVE->DECODE; ENCODE per-cell local NO neighbor reads; RESOLVE the only cross-cell step; DECODE per-cell local: derive T/v, relabel, write back) and #5 (move when force > resistance; yield/cohesion = threshold, viscosity = RATE never threshold). Audit step_world_b's actual pass order and locality: does ENCODE read any neighbor? does DECODE read any neighbor (relabel, sub-min, vacuum guard)? Is there any cross-cell work outside RESOLVE (snapshot? relaxation placement counts as RESOLVE per §6.1)? Check every gate in the flux/swap/displacement paths: is viscosity used as a threshold anywhere (vs rate/cadence)? Is any threshold built from viscosity (sqrt(visc) in swap cadence is PENDING-T8 — check it is cadence not threshold)?` },
  { key: 'law-6-7', focus: `Law clauses #6 (thermal rides the same pipeline: E on enthalpy curve persisted, T derived h-inverse(E/m), conduction = 6-face k_face flux harmonic mean, radiation, advection carries m_dot*h_donor AND m_dot*u_donor; max principle enforced CONSERVATIVELY by scaling FACE fluxes, never one-sided clip; no separate conduction pass) and #7 (store EXTENSIVE derive INTENSIVE: persisted set = matIx, mass, momentum, E, P + bookkeeping swapReady/void_ix ONLY; stored raw v or raw T = forbidden ghost drift). Audit: Chunk struct fields vs the law list (T_curr/T_next still present? swapReady absent pre-T8 = PENDING; anything else persisted?); where T is derived; conduction implementation (on E? inside RESOLVE? clamp style = one-sided clip is PENDING-T5 but verify how it's labeled); advection cargo (m_dot*h AND m_dot*u both carried? cross-cp pricing is PENDING-T7); radiation absent (PENDING-T6); enthalpy curve absent (PENDING-T7, current E=m*cp*T linear). Hunt NEW drift: any path writing a raw T/v as source of truth, any E mutation outside RESOLVE deposits/DECODE bookkeeping.` },
  { key: 'law-8-9', focus: `Law clauses #8 (LUT fixed schema: exact field list incl. emissivity, thermalExpansion, latentHeatMin/Max, phase quadruple, per-gas T_ref kept in ENGINE; chi formula + guard + gas means chi>0.999; viscosity rate axis +INF frozen; yieldStress threshold axis) and #9 (mass moves never vanishes: conservative antisymmetric flux donor-budget+receiver-room, or permutation swap; only source/sink = caller place/break separately ledgered; pushed cell with no escape = no-op; gas compresses + pushes back via live EOS p_eos=(m/M)RT/V - P0 gauge; incompressible relief = P rising; no_escape detection seam fires empty). Audit: Material struct + real LUT vs law #8 list field-by-field; chi implementation + guard + GAS_CHI_MIN; the injection path in sim_engine.hpp (place/break ledger? find_chain_hop still present = banned symbol, check how tracked); the no_escape seam (does it exist AT ALL? law says a seam must fire on no-escape — empty body OK, absent = drift); EOS formula exactness incl. P0 and T_ref fallback; any remaining mass deletion path (sub-min, eps_mass relabel — ledgered?).` },
  { key: 'spec-1-manifest', focus: `Spec §1.1 (persisted per cell: exactly { matIx, mass, momentum px py pz, E, P, swapReady, void_ix } + ONE P tick-boundary copy), §1.2 (LUT table values + chi margins air 0.99980 / steam 0.99946 / cutoff 0.999), §1.3 (FROZEN manifest: knob list with units/ranges — omega [1.0-1.9], kappa <= rho*dx^2/dt once-per-tick-first-sweep, N_relax [1-8], vel_damp [0-2], eps_mass 1e-6, k_c=3 cross-species-only, t_swap_min 0.5s, alpha_eos (0-1], T_sky 270, T_ref_global 288, per-gas T_ref; retired-symbol list). INV-3 = struct/knob diff vs §1.3 must be empty. DO THE DIFF: enumerate every Globals field and every Chunk per-cell array at c148f49 and diff against §1.3/§1.1. New knobs added by T2a/T2b/T3 that are NOT in §1.3 (e.g. LADDER_BETA, LADDER_REST_DEADBAND, GAS_CHI_MIN, R_GAS, any drag/swap_kc/swap_visc_rate leftovers, eviction slop constants) = manifest drift (DRIFT-NEW unless queued). Retired symbols truly zero. T_curr/T_next presence classification. Check vel_damp=1.0 and kappa=20 are in-range.` },
  { key: 'spec-2-3', focus: `Spec §2.1/§2.2 (EOS formulas exact: p_abs=(m/M)RT/V, P0 per-gas at its own T_ref, gauge; cross-gas faces compare ABSOLUTE pressure (p_eos+P0 each side) so per-gas offsets cancel — IS THIS IMPLEMENTED anywhere gas|gas faces matter?; incompressibles: no branch exists) and §3.1/§3.2 (relaxation: stencil cases verbatim, target average over n_open, gas source term alpha_eos*(p_eos - P) — code now uses an IMPLICIT source form (queued as part of item 2) — verify same fixed point claim; P update (1-omega)P + omega*target; kappa*divU first sweep only; solids excluded carry no P shield below; worked fixed points; O(H^2) honesty; Gauss-Seidel dataflow normative; per-color in-place). Audit code vs each sentence. Specifically hunt: the cross-gas ABSOLUTE-pressure comparison (air|steam faces) — implemented, absent, or moot?; sealed pure-Neumann region handling vs kappa claim; half-sweep pair structure 2*N_relax; any deviation in the gas source algebra beyond the queued implicit switch.` },
  { key: 'spec-4-5', focus: `Spec §4 (force formula + units; vel_damp defined u/(1+vd*dt) + KE->heat same cell; velocity invariants ||u||<=dx/dt; void floor m<eps_mass => u=0 with mass AND E ledgered to boundary ledger ~1J/1e-6kg; solid faces closed, v3 wall-reaction kick deleted, no transient read as impulse) and §5.1 (yield: net force vs tau_y*A_face; stacked chains direction-projected F_out = F_in + m*(g.n) - tau_y*A — IS the chain rule implemented at all? tau_y=inf solids never yield) + §5.2 (cohesion legalization exact algorithm + sigma composition, budget m - minMass with full-drain exception) + §5.3 (viscosity rate lambda = mu/(rho dx^2) damping 1/(1+dt*lambda) KEPT — verify this exact damping form is what ENCODE dragScale implements; swap cadence is PENDING-T8). Audit each sentence vs code. Hunt especially: the void-floor LEDGER (does ANY boundary ledger structure exist for the eps_mass path? momentum zeroed but E/mass ledgered where?); the §5.2 legalization order legalize-then-sigma; the viscous damping formula exactness (dragScale algebra vs 1/(1+dt*lambda)); yield gate presence/correctness for the implemented paths.` },
  { key: 'spec-6', focus: `Spec §6.1 (five passes R0/R1/R1.5/R2 + normative in-step order — PENDING-T4 as a structure, but the CURRENT monolith must still honor the underlying constraints), §6.2 (room = maxMass - m; gas large band; incompressible 0 at rest; transient overshoot forbidden except §8.4 freeze), §6.3 (cross-species movement = swap/displacement ONLY, no partial cross-species flux; displacement-swap = pure permutation with driver P_donor - Phi_anchor; gas-into-gas same-species flux + EOS OK), §6.4 (sub-min: never deleted, DECODE flags, next RESOLVE drains to strongest same-species neighbor; only m<eps_mass relabels to VACUUM with mass AND E ledgered; max(eps,min)->VACUUM rule is DEAD). Audit the live monolith against the CONSTRAINTS (not the pass structure): any partial cross-species flux path? any maxMass overshoot path besides freeze (vacuum recvRoom=INF is queued #19 — look for OTHERS, e.g. eviction kroom slop, injection)? sub-min deletion paths beyond eps (the dead max(eps,min) rule truly dead?); whole-cell vs per-face swap/flux exclusion (flux-XOR-swap honored by the current swap selection?).` },
  { key: 'spec-9-11', focus: `Spec §9 (dt-invariance: every rate*dt, every cadence in SECONDS — audit every dt use and every accumulator/cadence in the live code for tick-quantized behavior (swap_visc_rate period? hysteresis counters? cohesion streak gates in tests are test-side); order-independence INV-7: all passes read snapshot or prior-pass buffers — list every site that reads LIVE state mid-pass (gasSpent #15 queued, P relaxation in-place per color is sanctioned; hunt OTHERS e.g. vacClaim, canDrain, recvRoom live decrements, accumulator reads); buffers SoA list vs actual; no atomics; ledgers per-thread-block OUTSIDE hot path — current ledger structures?) and §11 (the invariant table: for each of the 15 rows state implemented-green / implemented-held(T3-OPEN) / absent(owner task). Also §10 honest-ledger table claims vs reality). Severity-rank any LIVE mid-pass read not queued.` },
  { key: 't1-data-jni', focus: `T1 surface: tests/engine_b_real_lut.hpp every cell vs spec §1.2 table (cp k M min/def/max mu tau_y eps beta latentMin latentMax phase quadruple T_ref_gas — re-verify independently, including ICE row and the latent mapping to min/max transitions); jni/orge_jni.cpp orgeRegisterMaterials defaults (T_ref_gas=0 convention + TODO(T10) accuracy; emissivity/beta/latent defaults; 12-array ABI unchanged); lut_schema_test pins (read the test, do its assertions actually pin what they claim); chi guard exactness incl. INF==INF band; Material struct field ORDER vs aggregate initializers across ALL test files (positional-init transposition risk); GAS_CHI_MIN usage — any remaining hardcoded 0.999 magic; defaultTemperature/pinned and other JAVA-side Material fields the engine schema ignores (note only). Also: grand_energy helper in real_lut.hpp uses m*cp*T_curr + kinetic — is that consistent with persisted-E-as-truth (law #7) or a stale test-side energy definition that will break at T7 (latent plateaus)? Classify.` },
]

const FINDINGS_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: { findings: { type: 'array', items: { type: 'object', additionalProperties: false,
    properties: {
      clause: { type: 'string' }, code_site: { type: 'string' }, description: { type: 'string' },
      classification: { type: 'string', enum: ['DRIFT-NEW','DRIFT-KNOWN','PENDING-IMPL','NOTE'] },
      severity: { type: 'string', enum: ['high','medium','low'] }, evidence: { type: 'string' },
    }, required: ['clause','code_site','description','classification','severity','evidence'] } } },
  required: ['findings'],
}

phase('Audit')
const results = (await parallel(DIMS.map(d => () =>
  agent(PRE + d.focus, { label: `audit:${d.key}`, phase: 'Audit', schema: FINDINGS_SCHEMA })
))).filter(Boolean)
const allFindings = results.flatMap((r, i) => r.findings.map(f => ({ ...f, dim: DIMS[i] ? DIMS[i].key : 'unknown' })))
log(`Audit complete: ${allFindings.length} raw findings across ${results.length}/10 dimensions`)

phase('Synthesize')
const SYNTH_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    new_unique: { type: 'array', items: { type: 'object', additionalProperties: false, properties: {
      id: { type: 'string' }, clause: { type: 'string' }, code_site: { type: 'string' },
      description: { type: 'string' }, severity: { type: 'string', enum: ['high','medium','low'] }, evidence: { type: 'string' },
    }, required: ['id','clause','code_site','description','severity','evidence'] } },
    known_confirmed: { type: 'array', items: { type: 'string' } },
    pending_notes: { type: 'array', items: { type: 'string' } },
    misclassified_dropped: { type: 'array', items: { type: 'string' } },
  },
  required: ['new_unique','known_confirmed','pending_notes','misclassified_dropped'],
}
const synth = await agent(
`You are the synthesis judge of a drift audit. Below are raw findings from 10 parallel auditors comparing engine code @ c148f49 against /home/claude/ORGE-B/docs/superpowers/DESIGN-LAW.md and specs/2026-06-10-engine-b-unified-spec-v4.md. The KNOWN drift queue is in docs/superpowers/handoffs/2026-06-11-engine-b-v4-T4-continuation.md (read it).

Your job: (1) DEDUP — auditors overlap; merge same-root-cause findings (same code site + same clause family) into one, keep the best evidence; (2) RE-CLASSIFY conservatively — anything matching the known queue or carrying an in-code labeled-debt comment with an owner = known/confirmed, NOT new; anything that is just an unimplemented future-task section = pending; (3) For the remainder — genuinely NEW drift — assign stable ids (ND-1, ND-2, ...), keep severity honest (high = physics/conservation/persisted-state; medium = formula/knob/manifest; low = wording/test-shape). You may spot-read the repo files to settle disagreements between auditors (read-only; NO test runs). When two auditors disagree about the same site, read the code and decide. List dropped/misclassified raw findings with one-line reasons so nothing silently disappears.

RAW FINDINGS (${allFindings.length}):
${JSON.stringify(allFindings, null, 1)}`,
  { label: 'synthesize', phase: 'Synthesize', schema: SYNTH_SCHEMA })
log(`Synthesis: ${synth.new_unique.length} NEW unique, ${synth.known_confirmed.length} known-confirmed, ${synth.pending_notes.length} pending, ${synth.misclassified_dropped.length} dropped`)

phase('Verify')
const VERDICT_SCHEMA = {
  type: 'object', additionalProperties: false,
  properties: {
    verdict: { type: 'string', enum: ['REAL-DRIFT','REFUTED','ALREADY-LABELED'] },
    reasoning: { type: 'string' }, severity: { type: 'string', enum: ['high','medium','low'] },
    suggested_disposition: { type: 'string' },
  }, required: ['verdict','reasoning','severity','suggested_disposition'],
}
const verified = await parallel(synth.new_unique.map(f => () =>
  parallel(['refute','label-check'].map(lens => () => agent(
`Adversarial verifier, lens = ${lens.toUpperCase()}. A drift audit of /home/claude/ORGE-B/ORGE-ENGINE @ c148f49 (vs /home/claude/ORGE-B/docs/superpowers/DESIGN-LAW.md + specs/2026-06-10-engine-b-unified-spec-v4.md) produced this candidate NEW drift finding:

${JSON.stringify(f, null, 1)}

${lens === 'refute'
  ? 'Try hard to REFUTE it: read the cited code and the cited clause yourself (read-only, NO test runs). Is the code actually compliant under a correct reading? Is the cited sentence being misread? Is the "drift" actually the spec-sanctioned behavior? Default to REFUTED only if you can articulate the compliant reading concretely; if the mismatch survives your attack, verdict REAL-DRIFT.'
  : 'Check whether it is ALREADY tracked: search the code around the cited site for labeled-debt comments (T3-OPEN, TODO(T10), DEBT, ARG-PARITY, owner-task labels), the known queue in docs/superpowers/handoffs/2026-06-11-engine-b-v4-T4-continuation.md, and commit messages (git log --oneline -12). If tracked anywhere with an owner, verdict ALREADY-LABELED (say where). Else REAL-DRIFT (or REFUTED if you happen to find it is not drift at all).'}

suggested_disposition: one sentence — what should happen (spec amendment / code fix in task T_n / test fix / record as accepted note).`,
    { label: `verify:${f.id}:${lens}`, phase: 'Verify', schema: VERDICT_SCHEMA })))
    .then(vs => ({ finding: f, lenses: vs.filter(Boolean) }))
))
const confirmed = verified.filter(Boolean).map(v => {
  const refute = v.lenses[0], label = v.lenses[1]
  const real = (!refute || refute.verdict !== 'REFUTED') && (!label || label.verdict === 'REAL-DRIFT')
  const tracked = label && label.verdict === 'ALREADY-LABELED'
  return { ...v.finding, verdictReal: real, verdictTracked: tracked,
           refuteReasoning: refute ? refute.reasoning : 'n/a', labelReasoning: label ? label.reasoning : 'n/a',
           disposition: (refute && refute.suggested_disposition) || (label && label.suggested_disposition) || '' }
})
log(`Verify: ${confirmed.filter(c => c.verdictReal).length} confirmed REAL new drift, ${confirmed.filter(c => c.verdictTracked).length} already-labeled, ${confirmed.filter(c => !c.verdictReal && !c.verdictTracked).length} refuted`)

return {
  rawCount: allFindings.length,
  newConfirmed: confirmed.filter(c => c.verdictReal),
  alreadyLabeled: confirmed.filter(c => c.verdictTracked && !c.verdictReal),
  refuted: confirmed.filter(c => !c.verdictReal && !c.verdictTracked),
  knownConfirmed: synth.known_confirmed,
  pendingNotes: synth.pending_notes,
  dropped: synth.misclassified_dropped,
}