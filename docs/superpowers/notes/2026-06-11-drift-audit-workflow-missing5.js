export const meta = {
  name: 'drift-audit-missing5',
  description: 'Drift audit — run the 5 MISSING dims, union with 5 salvaged dims, synth + adversarial verify (engine @ c148f49 vs law+v4)',
  phases: [
    { title: 'Audit', detail: '5 parallel finders (law-1-3, law-4-5, spec-1-manifest, spec-2-3, spec-9-11)' },
    { title: 'Synthesize', detail: 'union new+salvaged, dedup + classify' },
    { title: 'Verify', detail: 'adversarial refute + label-check per NEW finding' },
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
  { key: 'spec-1-manifest', focus: `Spec §1.1 (persisted per cell: exactly { matIx, mass, momentum px py pz, E, P, swapReady, void_ix } + ONE P tick-boundary copy), §1.2 (LUT table values + chi margins air 0.99980 / steam 0.99946 / cutoff 0.999), §1.3 (FROZEN manifest: knob list with units/ranges — omega [1.0-1.9], kappa <= rho*dx^2/dt once-per-tick-first-sweep, N_relax [1-8], vel_damp [0-2], eps_mass 1e-6, k_c=3 cross-species-only, t_swap_min 0.5s, alpha_eos (0-1], T_sky 270, T_ref_global 288, per-gas T_ref; retired-symbol list). INV-3 = struct/knob diff vs §1.3 must be empty. DO THE DIFF: enumerate every Globals field and every Chunk per-cell array at c148f49 and diff against §1.3/§1.1. New knobs added by T2a/T2b/T3 that are NOT in §1.3 (e.g. LADDER_BETA, LADDER_REST_DEADBAND, GAS_CHI_MIN, R_GAS, any drag/swap_kc/swap_visc_rate leftovers, eviction slop constants) = manifest drift (DRIFT-NEW unless queued). Retired symbols truly zero. T_curr/T_next presence classification. Check vel_damp=1.0 and kappa=20 are in-range.` },
  { key: 'spec-2-3', focus: `Spec §2.1/§2.2 (EOS formulas exact: p_abs=(m/M)RT/V, P0 per-gas at its own T_ref, gauge; cross-gas faces compare ABSOLUTE pressure (p_eos+P0 each side) so per-gas offsets cancel — IS THIS IMPLEMENTED anywhere gas|gas faces matter?; incompressibles: no branch exists) and §3.1/§3.2 (relaxation: stencil cases verbatim, target average over n_open, gas source term alpha_eos*(p_eos - P) — code now uses an IMPLICIT source form (queued as part of item 2) — verify same fixed point claim; P update (1-omega)P + omega*target; kappa*divU first sweep only; solids excluded carry no P shield below; worked fixed points; O(H^2) honesty; Gauss-Seidel dataflow normative; per-color in-place). Audit code vs each sentence. Specifically hunt: the cross-gas ABSOLUTE-pressure comparison (air|steam faces) — implemented, absent, or moot?; sealed pure-Neumann region handling vs kappa claim; half-sweep pair structure 2*N_relax; any deviation in the gas source algebra beyond the queued implicit switch.` },
  { key: 'spec-9-11', focus: `Spec §9 (dt-invariance: every rate*dt, every cadence in SECONDS — audit every dt use and every accumulator/cadence in the live code for tick-quantized behavior (swap_visc_rate period? hysteresis counters? cohesion streak gates in tests are test-side); order-independence INV-7: all passes read snapshot or prior-pass buffers — list every site that reads LIVE state mid-pass (gasSpent #15 queued, P relaxation in-place per color is sanctioned; hunt OTHERS e.g. vacClaim, canDrain, recvRoom live decrements, accumulator reads); buffers SoA list vs actual; no atomics; ledgers per-thread-block OUTSIDE hot path — current ledger structures?) and §11 (the invariant table: for each of the 15 rows state implemented-green / implemented-held(T3-OPEN) / absent(owner task). Also §10 honest-ledger table claims vs reality). Severity-rank any LIVE mid-pass read not queued.` },
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
log(`Audit complete: ${allFindings.length} raw findings across ${results.length}/5 missing dimensions`)

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
`You are the synthesis judge of a whole-arc drift audit. The engine @ c148f49 was audited across 10 law/spec dimensions in TWO batches. You must synthesize the UNION of both batches.

BATCH A (5 dimensions, ALREADY SALVAGED, RAW + UNVERIFIED): read the file
/home/claude/ORGE-B/docs/superpowers/notes/2026-06-11-drift-audit-partial-findings.md
in full. It contains ~81 findings across dimensions spec-4-5, law-6-7, law-8-9, spec-6, t1-data-jni, each already tagged [DRIFT-NEW]/[DRIFT-KNOWN]/[PENDING-IMPL]/[NOTE] with code sites and evidence. Treat every numbered item there as a raw finding equal in standing to Batch B below.

BATCH B (the 5 MISSING dimensions, freshly audited this run) is the JSON array at the bottom of this message.

Reference: DESIGN-LAW at /home/claude/ORGE-B/docs/superpowers/DESIGN-LAW.md, spec at specs/2026-06-10-engine-b-unified-spec-v4.md, and the KNOWN drift queue in handoffs/2026-06-11-engine-b-v4-T4-continuation.md (read it).

Your job over the FULL UNION (Batch A file + Batch B JSON):
(1) DEDUP — auditors across both batches overlap; merge same-root-cause findings (same code site + same clause family) into one, keep the best evidence and cite which dim(s) raised it.
(2) RE-CLASSIFY conservatively — anything matching the known queue or carrying an in-code labeled-debt comment with an owner = known_confirmed (list as a short string), NOT new; anything that is just an unimplemented future-task section = pending_notes.
(3) For the remainder — genuinely NEW drift not in the queue and not labeled in-code — assign stable ids (ND-1, ND-2, ...), keep severity honest (high = physics/conservation/persisted-state; medium = formula/knob/manifest; low = wording/test-shape). Carry forward the Batch A [DRIFT-NEW] items too — they were never adversarially verified, so they MUST land in new_unique to be verified downstream.
You may spot-read the repo files to settle disagreements (read-only; NO test runs). When auditors disagree about a site, read the code and decide. List dropped/misclassified raw findings in misclassified_dropped with one-line reasons so nothing silently disappears.

BATCH B RAW FINDINGS (${allFindings.length}):
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
  : 'Check whether it is ALREADY tracked: search the code around the cited site for labeled-debt comments (T3-OPEN, TODO(T10), DEBT, ARG-PARITY, owner-task labels), the known queue in docs/superpowers/handoffs/2026-06-11-engine-b-v4-T4-continuation.md, and commit messages (git log --oneline -16). If tracked anywhere with an owner, verdict ALREADY-LABELED (say where). Else REAL-DRIFT (or REFUTED if you happen to find it is not drift at all).'}

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
  rawCountBatchB: allFindings.length,
  newConfirmed: confirmed.filter(c => c.verdictReal),
  alreadyLabeled: confirmed.filter(c => c.verdictTracked && !c.verdictReal),
  refuted: confirmed.filter(c => !c.verdictReal && !c.verdictTracked),
  knownConfirmed: synth.known_confirmed,
  pendingNotes: synth.pendingNotes || synth.pending_notes,
  dropped: synth.misclassified_dropped,
}
